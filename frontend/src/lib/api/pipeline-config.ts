import { ApiError, apiRequest } from "@/lib/api/client";
import { getLossReasons, getPipeline, type LossReason, type Pipeline } from "@/lib/api/leads";

const stagesPath = "/v1/clinic/pipeline/stages";
const reasonsPath = "/v1/clinic/loss-reasons";
const headers = { "Content-Type": "application/json" };

function isVersion(value: number) {
  return Number.isSafeInteger(value) && value >= 0;
}

function isPipeline(value: Pipeline | null): value is Pipeline {
  return Boolean(value?.id && isVersion(value.version) && Array.isArray(value.stages) && Array.isArray(value.archivedStages));
}

function isLossReason(value: LossReason | null): value is LossReason {
  return Boolean(value?.id && typeof value.name === "string" && typeof value.archived === "boolean" && isVersion(value.version));
}

export async function loadPipelineConfig(signal: AbortSignal) {
  const [pipeline, reasons] = await Promise.all([getPipeline(signal), getLossReasons(signal, true)]);
  if (!isPipeline(pipeline) || !Array.isArray(reasons) || !reasons.every(isLossReason)) {
    throw new ApiError(200, "PIPELINE_CONFIG_UNAVAILABLE", "A API deste ambiente ainda não oferece o contrato de configuração do funil.");
  }
  return { pipeline, reasons };
}

async function writeStage(path: `/v1/${string}`, method: "POST" | "PUT", body: object, pipeline: Pipeline, confirms: (result: Pipeline) => boolean) {
  const result = await apiRequest<Pipeline | null>(path, { method, headers, body: JSON.stringify({ ...body, version: pipeline.version }) });
  if (!isPipeline(result) || result.id !== pipeline.id || result.version < pipeline.version || !confirms(result)) {
    throw new ApiError(200, "UNEXPECTED_RESPONSE", "A API não confirmou a configuração do funil.");
  }
}

export function saveStage(pipeline: Pipeline, name: string, id?: string) {
  return id
    ? writeStage(`${stagesPath}/${id}`, "PUT", { name }, pipeline, (result) => result.stages.some((stage) => stage.id === id && stage.name === name))
    : writeStage(stagesPath, "POST", { name }, pipeline, (result) => result.stages.some((stage) =>
      stage.kind === "OPEN" && stage.name === name && !pipeline.stages.some((previous) => previous.id === stage.id)));
}

export function reorderStages(pipeline: Pipeline, stageIds: string[]) {
  return writeStage(`${stagesPath}/order`, "PUT", { stageIds }, pipeline, (result) => {
    const open = result.stages.filter(({ kind }) => kind === "OPEN");
    return open.length === stageIds.length && open.every(({ id }, index) => id === stageIds[index]);
  });
}

export function archiveStage(pipeline: Pipeline, id: string, archived: boolean) {
  return writeStage(`${stagesPath}/${id}/archived`, "PUT", { archived }, pipeline, (result) =>
    (archived ? result.archivedStages : result.stages).some((stage) => stage.id === id));
}

async function writeReason(path: `/v1/${string}`, method: "POST" | "PUT", body: object, confirms: (result: LossReason) => boolean, reason?: LossReason) {
  const result = await apiRequest<LossReason | null>(path, { method, headers, body: JSON.stringify(body) });
  if (!isLossReason(result) || (reason && (result.id !== reason.id || result.version < reason.version)) || !confirms(result)) {
    throw new ApiError(200, "UNEXPECTED_RESPONSE", "A API não confirmou o motivo de perda.");
  }
}

export function saveLossReason(name: string, reason?: LossReason) {
  const confirms = (result: LossReason) => result.name === name;
  return reason ? writeReason(`${reasonsPath}/${reason.id}`, "PUT", { name, version: reason.version }, confirms, reason)
    : writeReason(reasonsPath, "POST", { name }, confirms);
}

export function archiveLossReason(reason: LossReason, archived: boolean) {
  return writeReason(`${reasonsPath}/${reason.id}/archived`, "PUT", { archived }, (result) => result.archived === archived, reason);
}
