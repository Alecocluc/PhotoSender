import {
  state, refreshStatus, refreshHistory, refreshIPs, refreshSettings, refreshHost, refreshServerState,
  refreshJobs, refreshDevices, noteArrival, sessionActive, rows,
} from './state.js';
import { entryKey, sortedIPs } from './utils.js';
import { pruneFresh } from './components.js';
import { clearThumbnails } from './thumbs.js';
import { applyTheme, renderNav, renderStation } from './shell.js';
import { navigate, rerender } from './router.js';
import './views/receiver.js';
import './views/photos.js';
import './views/history.js';
import './views/settings.js';

renderNav();
document.querySelectorAll('.nav-item').forEach((el) => el.addEventListener('click', () => navigate(el.dataset.view)));
document.querySelector('#theme-toggle').addEventListener('click', async () => {
  const next = { system: 'light', light: 'dark', dark: 'system' }[state.settings?.theme || 'system'];
  await window.api.updateSettings({ theme: next });
  await refreshSettings(); applyTheme();
  if (state.view === 'settings') rerender();
});
document.querySelector('#open-folder-btn').addEventListener('click', () => window.api.openFolder());

let pending = null;
function scheduleRender(delay = 400) {
  if (pending || document.hidden) return;
  pending = setTimeout(() => { pending = null; if (!document.hidden) rerender(); }, delay);
}
window.api.onFileReceived((entry) => {
  noteArrival(entry);
  pruneFresh();
  if (!document.hidden) state.fresh.set(entryKey(entry), performance.now());
  scheduleRender(state.view === 'receiver' ? 300 : 1000);
});
window.api.onFilesRemoved?.(async () => {
  clearThumbnails();
  await Promise.all([refreshHistory(), refreshStatus()]);
  renderStation(); scheduleRender();
});
window.api.onJobsChanged?.(async (payload) => {
  if (Array.isArray(payload) || Array.isArray(payload?.items)) state.jobs = rows(payload);
  else await refreshJobs();
  renderStation(); if (state.view === 'receiver') scheduleRender(100);
});
window.api.onDevicesChanged?.(async (payload) => {
  if (Array.isArray(payload) || Array.isArray(payload?.items)) state.devices = rows(payload);
  else await refreshDevices();
  state.revision += 1;
  scheduleRender(100);
});
async function refreshAddresses() {
  const before = sortedIPs(state.ips).join(',');
  await refreshIPs();
  if (sortedIPs(state.ips).join(',') !== before) {
    renderStation();
    if (['receiver', 'settings'].includes(state.view)) scheduleRender();
  }
}
window.api.onIpsChanged?.(() => { if (!state.initializing) refreshAddresses(); });
window.addEventListener('focus', () => { if (!state.initializing) refreshAddresses(); });
window.api.onServerState((next) => {
  state.server = { ...state.server, ...next };
  if (state.initializing) return;
  renderStation(); scheduleRender();
});

// Exactly one status request at a time. Hidden windows do no presentation work.
let polling = false, lastPoll = 0, libraryId = '';
async function poll(force = false) {
  if (state.initializing || polling || document.hidden) return;
  if (!force && Date.now() - lastPoll < (sessionActive() ? 1500 : 7000)) return;
  polling = true;
  try {
    await refreshStatus();
    if (libraryId && state.status?.libraryId !== libraryId) {
      clearThumbnails(); await refreshHistory(); state.revision += 1;
    }
    libraryId = state.status?.libraryId || libraryId;
    renderStation();
    if (state.view === 'receiver') rerender();
  } finally { polling = false; lastPoll = Date.now(); }
}
setInterval(() => poll(), 1000);
document.addEventListener('visibilitychange', async () => {
  if (document.hidden || state.initializing) return;
  await Promise.all([poll(true), refreshHistory({ replaceCollection: false }), refreshDevices()]);
  renderStation(); rerender();
});

(async () => {
  await Promise.all([refreshSettings(), refreshServerState()]);
  applyTheme(); navigate('receiver');
  await Promise.all([refreshStatus(), refreshHistory(), refreshIPs(), refreshHost(), refreshJobs(), refreshDevices()]);
  libraryId = state.status?.libraryId || '';
  state.initializing = false;
  renderStation(); rerender();
})();
