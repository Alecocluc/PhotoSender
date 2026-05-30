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

// Append the next page of transfers onto state.history. Shared by the History and Gallery views so
// both can page past the initial 100; each re-renders itself after awaiting. Throws on failure so
// callers can restore their button.
export async function loadMoreHistory() {
  const current = state.history.items || [];
  const offset = Number(state.history.nextOffset ?? current.length);
  const page = await window.api.getHistory({ limit: 100, offset });
  state.history = {
    ...page,
    items: [...current, ...(page.items || [])],
    totalCount: page.totalCount ?? state.history.totalCount ?? current.length,
    nextOffset: page.nextOffset ?? (current.length + (page.items || []).length),
    hasMore: !!page.hasMore,
  };
  return state.history;
}

export async function refreshIPs() {
  try { state.ips = await window.api.getLocalIPs(); } catch { state.ips = []; }
}

export async function refreshSettings() {
  try { state.settings = await window.api.getSettings(); } catch { /* ignore */ }
}
