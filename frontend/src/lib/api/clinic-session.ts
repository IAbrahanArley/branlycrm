const name = "branly-clinic-session";

export async function withClinicSession<T>(mode: LockMode, action: () => Promise<T>, signal?: AbortSignal): Promise<T> {
  const locks = typeof navigator === "undefined" ? undefined : navigator.locks;
  return locks ? locks.request(name, { mode, signal }, action) : action();
}

export function notifyClinicChange() {
  if (typeof BroadcastChannel === "undefined") return;
  const channel = new BroadcastChannel(name);
  channel.postMessage("changed");
  channel.close();
}

export function onClinicChange(listener: () => void) {
  if (typeof BroadcastChannel === "undefined") return () => {};
  const channel = new BroadcastChannel(name);
  channel.onmessage = (event) => { if (event.data === "changed") listener(); };
  return () => channel.close();
}
