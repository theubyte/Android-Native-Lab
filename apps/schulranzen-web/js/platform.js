// What depends on the browser: service worker and updates, install prompt, durable storage.

let installEvent = null;
const listeners = new Set();
const notify = () => { for (const f of listeners) f(); };

export function onPlatformChange(f) {
  listeners.add(f);
  return () => listeners.delete(f);
}

window.addEventListener('beforeinstallprompt', (e) => {
  e.preventDefault();
  installEvent = e;
  notify();
});
window.addEventListener('appinstalled', () => {
  installEvent = null;
  notify();
});

export const canPromptInstall = () => installEvent !== null;

export async function promptInstall() {
  if (!installEvent) return false;
  const e = installEvent;
  installEvent = null;
  e.prompt();
  const choice = await e.userChoice;
  notify();
  return choice.outcome === 'accepted';
}

export const isStandalone = () => window.matchMedia('(display-mode: standalone)').matches || window.navigator.standalone === true;

/** iPadOS presents itself as a Mac; the touch points give it away. */
export function isIos() {
  const ua = navigator.userAgent;
  return /iPad|iPhone|iPod/.test(ua) || (ua.includes('Macintosh') && navigator.maxTouchPoints > 1);
}

export function isSafari() {
  const ua = navigator.userAgent;
  return /Safari/.test(ua) && !/Chrome|Chromium|CriOS|Edg|OPR|Android/.test(ua);
}

/** Safari may delete a site's data after a while without a visit; installed apps fare better. */
export const dataAtRisk = () => isSafari() || isIos();

export async function storageStatus() {
  const out = { persisted: null, usage: null, quota: null };
  try {
    if (navigator.storage?.persisted) out.persisted = await navigator.storage.persisted();
    if (navigator.storage?.estimate) {
      const e = await navigator.storage.estimate();
      out.usage = e.usage ?? null;
      out.quota = e.quota ?? null;
    }
  } catch { /* status stays unknown */ }
  return out;
}

export async function askPersist() {
  try {
    if (!navigator.storage?.persist) return null;
    return await navigator.storage.persist();
  } catch {
    return null;
  }
}

// ---- Service worker -------------------------------------------------------

let waiting = null;
let updateListener = () => {};

export const onUpdateReady = (f) => { updateListener = f; if (waiting) f(); };

function track(reg) {
  const check = (w) => {
    if (w && navigator.serviceWorker.controller) { waiting = w; updateListener(); }
  };
  check(reg.waiting);
  reg.addEventListener('updatefound', () => {
    const w = reg.installing;
    if (!w) return;
    w.addEventListener('statechange', () => {
      if (w.state === 'installed') check(w);
    });
  });
}

export async function registerWorker() {
  if (!('serviceWorker' in navigator)) return null;
  try {
    const reg = await navigator.serviceWorker.register('sw.js');
    track(reg);
    // A long-running installed app should still hear about new versions.
    const look = () => { reg.update().catch(() => {}); };
    setInterval(look, 60 * 60 * 1000);
    document.addEventListener('visibilitychange', () => { if (!document.hidden) look(); });
    let reloading = false;
    navigator.serviceWorker.addEventListener('controllerchange', () => {
      if (reloading || !applyRequested) return;
      reloading = true;
      location.reload();
    });
    return reg;
  } catch {
    return null;
  }
}

let applyRequested = false;

/** Takes the new version into use; the page reloads when it has taken over. */
export function applyUpdate() {
  if (!waiting) return;
  applyRequested = true;
  waiting.postMessage('skipWaiting');
}

export async function offlineReady() {
  if (!('serviceWorker' in navigator)) return false;
  const reg = await navigator.serviceWorker.getRegistration();
  return !!reg?.active;
}
