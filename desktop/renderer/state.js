import { entryKey } from './utils.js';

export const state = {
  view: 'receiver', initializing: true, settings: null, status: null,
  history: { items: [], totalCount: 0 }, ips: [],
  host: { hostname: '', platform: '', version: '' },
  server: { running: null, port: 3210, error: null },
  rebuild: { running: false, indexed: 0, total: 0 },
  fresh: new Map(), jobs: [], devices: [], revision: 0, collectionEpoch: 0,
};

export function rows(value) { return Array.isArray(value) ? value : value?.items || []; }
export function jobState(job) { return job.state || job.status || 'waiting'; }
export function activeJobs() {
  return state.jobs.filter((job) => ['planning', 'waiting', 'running', 'receiving', 'paused'].includes(jobState(job)));
}
/** Restart warnings use authoritative jobs AND streams, including the first large video. */
export function sessionActive() { return activeJobs().length > 0 || !!state.status?.receiving?.length; }
export function sendingName() { return activeJobs()[0]?.deviceName || state.status?.receiving?.[0]?.device || 'A phone'; }

/** The arrival strip is bounded independently from the permanent media inventory. */
export function noteArrival(entry) {
  const old = state.history;
  const cap = Number(old.historyCap) || 5000;
  const previous = old.items || [];
  const duplicate = previous.some((item) => entryKey(item) === entryKey(entry));
  const items = duplicate ? previous : [entry, ...previous].slice(0, 100);
  const totalCount = Math.min(cap, (Number(old.totalCount) || 0) + (duplicate ? 0 : 1));
  state.history = { ...old, items, totalCount, nextOffset: items.length, hasMore: items.length < totalCount };
  state.revision += 1;
}

export async function refreshStatus() {
  try {
    const next = await window.api.getStatus();
    if (!next || next.success === false) return;
    state.status = next;
    if (next.jobs) state.jobs = rows(next.jobs);
    if (next.devices) state.devices = rows(next.devices);
  } catch { /* Keep the last known facts while reconnecting. */ }
}
export async function refreshJobs() {
  if (!window.api.getJobs) return;
  try { state.jobs = rows(await window.api.getJobs()); } catch { /* Keep existing jobs. */ }
}
export async function refreshDevices() {
  if (!window.api.getDevices) return;
  try { state.devices = rows(await window.api.getDevices()); } catch { /* Keep existing devices. */ }
}
export async function refreshHistory({ replaceCollection = true } = {}) {
  try {
    const page = await window.api.getHistory({ limit: 100, offset: 0 });
    if (!page || page.success === false) return;
    state.history = page;
    if (replaceCollection) state.collectionEpoch += 1;
    state.revision += 1;
  } catch { /* Keep the last known arrivals. */ }
}
export async function refreshServerState() {
  try {
    const next = await window.api.getServerState();
    if (next) state.server = { ...state.server, ...next };
  } catch { state.server = { ...state.server, running: false, error: 'Receiver unavailable' }; }
}
export async function refreshIPs() {
  try { state.ips = await window.api.getLocalIPs(); } catch { state.ips = []; }
}
export async function refreshSettings() {
  try { state.settings = await window.api.getSettings(); } catch { /* Keep last settings. */ }
}
export async function refreshHost() {
  try {
    const info = await window.api.getHostInfo?.();
    if (info) state.host = { ...state.host, ...info };
  } catch { /* Keep generic label. */ }
}
export function computerName() { return state.host.hostname || 'This computer'; }
