import {
  state, refreshStatus, refreshHistory, refreshIPs, refreshSettings, refreshHost, refreshServerState,
  noteArrival, sessionActive,
} from './state.js';
import { entryKey, sortedIPs } from './utils.js';
import { pruneFresh } from './components.js';
import { applyTheme, renderNav, renderStation } from './shell.js';
import { navigate, rerender } from './router.js';
import './views/receiver.js';
import './views/photos.js';
import './views/history.js';
import './views/settings.js';

renderNav();

document.querySelectorAll(".nav-item").forEach((el) => {
  el.addEventListener("click", () => navigate(el.dataset.view));
});

document.querySelector("#theme-toggle").addEventListener("click", async () => {
  const cur = state.settings?.theme || "system";
  const next = { system: "light", light: "dark", dark: "system" }[cur];
  await window.api.updateSettings({ theme: next });
  await refreshSettings();
  applyTheme();
  if (state.view === "settings") rerender();
});

document.querySelector("#open-folder-btn").addEventListener("click", () => window.api.openFolder());

// Arrivals come in bursts of hundreds during a backup; coalesce re-renders so the window stays calm.
let pending = null;
function scheduleRender(delay) {
  if (pending) return;
  pending = setTimeout(() => {
    pending = null;
    if (["receiver", "photos", "history"].includes(state.view)) rerender();
  }, delay);
}

// The in-memory ledger keeps the newest LIVE_CAP arrivals (or as deep as the reader paged with Load
// more), so Photos and History re-render a bounded list during a 20,000-file backup. Items are the
// newest entries in order, so the next server page always starts at items.length.
const LIVE_CAP = 300;

window.api.onFileReceived((entry) => {
  const items = [entry, ...(state.history.items || [])];
  const cap = Math.max(LIVE_CAP, Number(state.history.pinned) || 0);
  if (items.length > cap) items.length = cap;
  state.history.items = items;
  state.history.totalCount = (state.history.totalCount || 0) + 1;
  state.history.nextOffset = Math.min(items.length, state.history.totalCount);
  state.history.hasMore = items.length < state.history.totalCount;
  if (state.status) {
    state.status.totalReceived = (state.status.totalReceived || 0) + 1;
    state.status.totalBytes = (state.status.totalBytes || 0) + (entry.size || 0);
    state.status.recentActivity = [entry, ...(state.status.recentActivity || [])].slice(0, 50);
  }
  noteArrival(entry);
  pruneFresh();
  state.fresh.set(entryKey(entry), performance.now());
  renderStation();
  scheduleRender(state.view === "receiver" ? 250 : 900);
});

// A phone's Sync deleted files here: re-read the ledger and totals so no view shows what's gone.
window.api.onFilesRemoved?.(async () => {
  await Promise.all([refreshHistory(), refreshStatus()]);
  renderStation();
  if (["receiver", "photos", "history"].includes(state.view)) rerender();
});

// Addresses change when Wi-Fi comes up after sign-in or DHCP hands out a new one. Only re-render
// when they did change, so the ticket (and focus in it) isn't rebuilt for nothing.
async function refreshAddresses() {
  const before = sortedIPs(state.ips).join(",");
  await refreshIPs();
  if (sortedIPs(state.ips).join(",") === before) return;
  renderStation();
  if (["receiver", "settings"].includes(state.view)) rerender();
}
window.api.onIpsChanged?.(() => { if (!state.initializing) refreshAddresses(); });
window.addEventListener("focus", () => { if (!state.initializing) refreshAddresses(); });

window.api.onServerState((s) => {
  state.server = { ...state.server, ...s };
  if (state.initializing) return; // boot renders the station and the view once everything is read
  renderStation();
  if (["receiver", "settings"].includes(state.view)) rerender();
});

// Speed and session freshness: poll quickly while a phone is sending, lazily otherwise.
let lastPoll = 0;
setInterval(async () => {
  // A phone is sending if files arrived lately or an upload is still streaming in.
  const live = sessionActive() || (state.status?.receiving?.length || 0) > 0;
  const due = Date.now() - lastPoll >= (live ? 2000 : 10000);
  if (!due) return;
  lastPoll = Date.now();
  await refreshStatus();
  renderStation();
  if (state.view === "receiver") rerender();
}, 1000);

(async () => {
  await Promise.all([refreshSettings(), refreshServerState()]);
  applyTheme();
  state.server.port = state.settings?.port || state.server.port || 3210;
  navigate("receiver");
  await Promise.all([refreshStatus(), refreshHistory(), refreshIPs(), refreshHost()]);
  state.initializing = false;
  renderStation();
  rerender();
})();
