import { entryKey } from './utils.js';

export const state = {
  view: "receiver",
  initializing: true,
  settings: null,
  status: null,
  history: { items: [], totalCount: 0 },
  ips: [],
  host: { hostname: "", platform: "", version: "" },
  /** running is null until the main process says whether the port opened (see refreshServerState). */
  server: { running: null, port: 3210, error: null },
  rebuild: { running: false, indexed: 0, total: 0 },
  /**
   * Entry key -> performance.now() of its arrival, for arrivals while the window is open. Each frame
   * develops once on that clock (components.js); pruneFresh() drops keys that have finished.
   */
  fresh: new Map(),
  /**
   * The phone currently sending, inferred from arrivals (the server has no notion of a "job"):
   * a burst of files with gaps under SESSION_GAP_MS. Ends on its own once the phone goes quiet.
   */
  session: null,
};

export const SESSION_GAP_MS = 20000;

/** Fold one arrival into the live session, starting a new one after a quiet gap. */
export function noteArrival(entry) {
  const now = Date.now();
  const s = state.session;
  if (!s || now - s.lastAt > SESSION_GAP_MS) {
    state.session = { startedAt: now, lastAt: now, count: 1, bytes: entry.size || 0, device: entry.deviceName || "", lastName: entry.fileName || "" };
  } else {
    s.lastAt = now;
    s.count += 1;
    s.bytes += entry.size || 0;
    if (entry.deviceName) s.device = entry.deviceName;
    s.lastName = entry.fileName || s.lastName;
  }
}

export function sessionActive() {
  return !!state.session && Date.now() - state.session.lastAt <= SESSION_GAP_MS;
}

export async function refreshStatus() {
  try { state.status = await window.api.getStatus(); } catch { /* ignore */ }
}

export async function refreshHistory() {
  try { state.history = await window.api.getHistory({ limit: 100, offset: 0 }); } catch { /* ignore */ }
}

// Append the next page of transfers onto state.history. Shared by the History and Photos views so
// both can page past the initial 100; each re-renders itself after awaiting. Throws on failure so
// callers can restore their button.
//
// Arrivals keep landing while the page is in flight (app.js prepends them to state.history.items in
// place), so the page is merged into the list as it is *after* the await, not the one it was asked
// from. The ledger is newest first: every arrival during the request moves the older entries one
// place down, so the next offset moves down by the same count. A page item that is already in the
// list means the server counted some of those arrivals before it read the page: those overlap,
// are dropped, and don't advance the offset.
//
// app.js also trims the tail of the list to its cap on each arrival (it builds a new array, so
// `startItems` still holds the entries as they were). Those trimmed entries sit between the live list
// and the new page, so they go back in first; otherwise the ledger would skip them.
export async function loadMoreHistory() {
  const base = state.history;
  const startItems = base.items || [];
  const offset = Number(base.nextOffset ?? startItems.length);
  const totalAtStart = Number(base.totalCount) || 0;
  const page = await window.api.getHistory({ limit: 100, offset });
  const older = page?.items || [];
  // The bridge answers a receiver that isn't answering with an empty page rather than an error.
  // Leave state.history untouched so the list doesn't claim it is complete.
  if (!older.length) throw new Error("No older transfers came back");
  // Cleared, imported or re-read (a phone's Sync deleted files) while the page was in flight: the
  // offset belongs to a list that no longer exists. Keep the fresh list; Load more starts over from it.
  if (state.history !== base) return state.history;

  const latest = state.history;
  const current = latest.items || [];
  const arrivals = Math.max(0, (Number(latest.totalCount) || 0) - totalAtStart);
  const seen = new Set(current.map(entryKey));
  const items = [...current];
  for (const item of startItems) {
    const key = entryKey(item);
    if (seen.has(key)) continue;
    seen.add(key);
    items.push(item);
  }
  let overlap = 0;
  for (const item of older) {
    const key = entryKey(item);
    if (seen.has(key)) { overlap += 1; continue; }
    seen.add(key);
    items.push(item);
  }
  const pageNext = Number(page.nextOffset ?? (offset + older.length));
  state.history = {
    ...latest,
    ...page,
    items,
    totalCount: Math.max(Number(page.totalCount) || 0, Number(latest.totalCount) || 0, items.length),
    nextOffset: Math.max(0, pageNext + arrivals - overlap),
    hasMore: !!page.hasMore,
    // How deep the reader paged on purpose; live arrivals never trim the list below this.
    pinned: items.length,
  };
  return state.history;
}

/** Ask the main process whether the receiver is up. Older main processes can't say: assume it is. */
export async function refreshServerState() {
  try {
    if (typeof window.api.getServerState === "function") {
      const s = await window.api.getServerState();
      if (s) { state.server = { ...state.server, ...s }; return; }
    }
  } catch { /* fall through */ }
  if (state.server.running === null) state.server.running = true;
}

export async function refreshIPs() {
  try { state.ips = await window.api.getLocalIPs(); } catch { state.ips = []; }
}

export async function refreshSettings() {
  try { state.settings = await window.api.getSettings(); } catch { /* ignore */ }
}

/** Host name for "ALEX-PC is receiving". Optional API: older main processes don't expose it. */
export async function refreshHost() {
  try {
    if (typeof window.api.getHostInfo === "function") {
      const info = await window.api.getHostInfo();
      if (info) state.host = { ...state.host, ...info };
    }
  } catch { /* keep the generic label */ }
}

export function computerName() {
  return state.host.hostname || "This computer";
}
