export function invitationTokenFromFragment(fragment: string) {
  const tokens = new URLSearchParams(fragment.replace(/^#/, "")).getAll("token");
  return tokens.length === 1 && /^[A-Za-z0-9_-]{43}$/.test(tokens[0]) ? tokens[0] : null;
}

export function validInvitationPassword(password: string) {
  return [...password].length >= 12 && new TextEncoder().encode(password).length <= 72;
}
