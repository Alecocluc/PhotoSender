export const state = {
  view: "dashboard",
  initializing: true,
  settings: null,
  status: null,
  history: { items: [], totalCount: 0 },
  ips: [],
  server: { running: true, port: 3210, error: null },
  startedAt: Date.now(),
  rebuild: { running: false, indexed: 0, total: 0 },
};

export async function refreshStatus() {
  try { state.status = await window.api.getStatus(); } catch { /* ignore */ }
}

export async function refreshHistory() {
  try { state.history = await window.api.getHistory({ limit: 100, offset: 0 }); } catch { /* ignore */ }
}

export async function refreshIPs() {
  try { state.ips = await window.api.getLocalIPs(); } catch { state.ips = []; }
}

export async function refreshSettings() {
  try { state.settings = await window.api.getSettings(); } catch { /* ignore */ }
}
