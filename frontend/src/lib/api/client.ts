type CsrfToken = {
  token: string;
  headerName: string;
};

type RequestOptions = {
  method?: "GET" | "HEAD" | "POST" | "PUT" | "PATCH" | "DELETE";
  body?: BodyInit;
  headers?: HeadersInit;
  signal?: AbortSignal;
};

type SessionEnd = "AUTHENTICATION_REQUIRED" | "SESSION_REVOKED";
type MfaGate = "MFA_REQUIRED" | "MFA_ENROLLMENT_REQUIRED";

const csrfRoute = "/v1/auth/csrf";
const sessionListeners = new Set<(reason: SessionEnd) => void>();
const mfaListeners = new Set<(reason: MfaGate) => void>();
let csrfRequest: Promise<CsrfToken> | undefined;
let sessionVersion = 0;

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly fields: readonly string[];
  readonly retryAfter: string | null;
  readonly contactId: string | null;

  constructor(
    status: number,
    code: string,
    message: string,
    fields: readonly string[] = [],
    retryAfter: string | null = null,
    contactId: string | null = null,
  ) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.fields = fields;
    this.retryAfter = retryAfter;
    this.contactId = contactId;
  }
}

export function onSessionEnd(listener: (reason: SessionEnd) => void) {
  sessionListeners.add(listener);
  return () => {
    sessionListeners.delete(listener);
  };
}

export function onMfaGate(listener: (reason: MfaGate) => void) {
  mfaListeners.add(listener);
  return () => { mfaListeners.delete(listener); };
}

export function isMfaGate(error: unknown) {
  return error instanceof ApiError && (
    (error.status === 401 && error.code === "MFA_REQUIRED") ||
    (error.status === 403 && error.code === "MFA_ENROLLMENT_REQUIRED")
  );
}

export function sessionEndMessage(reason: SessionEnd) {
  return reason === "SESSION_REVOKED"
    ? "Sessão encerrada. Entre novamente para acessar o painel."
    : "Entre para acessar o painel.";
}

async function readError(response: Response) {
  const body: Partial<{ code: unknown; message: unknown; fields: unknown; contactId: unknown }> | null =
    await response.json().catch(() => null);
  const code = typeof body?.code === "string" && body.code
    ? body.code
    : "UNEXPECTED_RESPONSE";
  const fields = Array.isArray(body?.fields)
    ? body.fields.filter((field): field is string => typeof field === "string")
    : [];
  const fallback = response.status === 403
    ? "Acesso negado."
    : "Não foi possível concluir a solicitação. Tente novamente.";
  const message = code === "STATE_CONFLICT"
    ? "A operação conflita com o estado atual. Recarregue os dados e tente novamente."
    : typeof body?.message === "string" && body.message.trim()
    ? body.message
    : fallback;

  const contactId = code === "CONTACT_DUPLICATE_PHONE" && typeof body?.contactId === "string" ? body.contactId : null;
  return new ApiError(response.status, code, message, fields, response.headers.get("Retry-After"), contactId);
}

async function send(path: string, options: RequestOptions) {
  try {
    return await fetch(`/api${path}`, {
      ...options,
      credentials: "same-origin",
      cache: "no-store",
      redirect: "error",
    });
  } catch (error) {
    if (options.signal?.aborted) throw error;
    throw new ApiError(0, "NETWORK_ERROR", "Não foi possível conectar à API. Tente novamente.");
  }
}

async function readJson<T>(response: Response): Promise<T> {
  try {
    return await response.json();
  } catch {
    throw new ApiError(response.status, "UNEXPECTED_RESPONSE", "A API retornou uma resposta inválida.");
  }
}

function handleError(error: ApiError) {
  if (isMfaGate(error)) {
    mfaListeners.forEach((listener) => listener(error.code as MfaGate));
    return;
  }
  if (error.status !== 401 || error.code === "INVALID_CREDENTIALS") return;

  csrfRequest = undefined;
  sessionVersion += 1;
  const reason = error.code === "SESSION_REVOKED"
    ? "SESSION_REVOKED"
    : "AUTHENTICATION_REQUIRED";
  sessionListeners.forEach((listener) => listener(reason));
}

function getCsrf(): Promise<CsrfToken> {
  if (csrfRequest) return csrfRequest;

  const pending = (async () => {
    const response = await send(csrfRoute, { method: "GET" });
    if (!response.ok) throw await readError(response);
    const csrf = await readJson<Partial<CsrfToken> | null>(response);
    if (typeof csrf?.token !== "string" || !csrf.token ||
        typeof csrf.headerName !== "string" || !csrf.headerName) {
      throw new ApiError(response.status, "UNEXPECTED_RESPONSE", "A API retornou um CSRF inválido.");
    }
    return { token: csrf.token, headerName: csrf.headerName };
  })().catch((error: unknown) => {
    if (csrfRequest === pending) {
      csrfRequest = undefined;
      if (error instanceof ApiError) handleError(error);
    }
    throw error;
  });

  csrfRequest = pending;
  return pending;
}

export function apiRequest<T>(path: `/v1/${string}`, options?: RequestOptions): Promise<T>;
export function apiRequest(
  path: `/v1/${string}`,
  options: RequestOptions & { response: "empty" },
): Promise<void>;
export async function apiRequest(
  path: `/v1/${string}`,
  { response: expected, method = "GET", ...options }: RequestOptions & {
    response?: "empty";
  } = {},
): Promise<unknown> {
  const pathname = path.split("?", 1)[0];
  if (!/^\/v1\/[\w/-]+$/.test(pathname) || path.includes("#")) {
    throw new TypeError("Rota da API inválida.");
  }

  const headers = new Headers(options.headers);
  headers.set("Accept", "application/json");
  const requestSession = sessionVersion;
  let usedCsrf: Promise<CsrfToken> | undefined;
  if (method !== "GET" && method !== "HEAD") {
    usedCsrf = getCsrf();
    const csrf = await usedCsrf;
    if (usedCsrf !== csrfRequest) {
      throw new ApiError(0, "SESSION_CHANGED", "A sessão mudou. Tente novamente.");
    }
    headers.set(csrf.headerName, csrf.token);
  }

  const response = await send(path, { ...options, headers, method });
  if (!response.ok) {
    const error = await readError(response);
    if (error.code === "CSRF_INVALID" && csrfRequest === usedCsrf) csrfRequest = undefined;
    if (!options.signal?.aborted && requestSession === sessionVersion) handleError(error);
    throw error;
  }

  if (response.status === 204 && method === "POST" &&
      (pathname === "/v1/auth/login" || pathname === "/v1/auth/logout" || pathname === "/v1/auth/mfa/verify")) {
    csrfRequest = undefined;
    sessionVersion += 1;
    // A sessão já mudou mesmo se a renovação falhar. A próxima mutação pode buscar outro token.
    await getCsrf().catch(() => undefined);
  }
  if ((response.status === 200 && method === "POST" && pathname === "/v1/auth/mfa/totp/confirm") ||
      (response.status === 204 && method === "DELETE" && pathname === "/v1/auth/mfa/totp")) {
    // A troca ou remoção pode revogar a sessão. Preserve os códigos retornados até a confirmação da pessoa.
    csrfRequest = undefined;
    sessionVersion += 1;
  }

  if (expected === "empty") {
    if (response.status === 204) return;
    throw new ApiError(response.status, "UNEXPECTED_RESPONSE", "A API retornou uma resposta inválida.");
  }
  const result = await readJson(response);
  if ((method === "GET" || method === "HEAD") && requestSession !== sessionVersion) {
    throw new ApiError(0, "SESSION_CHANGED", "A sessão mudou. Tente novamente.");
  }
  return result;
}
