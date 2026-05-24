import { state, refreshHistory, refreshStatus, refreshSettings } from './state.js';
import { fmtBytes } from './utils.js';
import { rerender } from './router.js';
import { applyTheme, renderFooter } from './shell.js';

let rebuildPoller = null;

export function updateRebuildButton() {
  const btn = document.querySelector("#rebuild-index-settings");
  const cleanBtn = document.querySelector("#clean-duplicates-settings");
  if (cleanBtn) cleanBtn.disabled = state.rebuild.running;
  if (!btn) return;
  if (state.rebuild.running) {
    btn.disabled = true;
    const { indexed, total } = state.rebuild;
    const label = total > 0
      ? `Hashing ${indexed.toLocaleString()} / ${total.toLocaleString()}…`
      : "Scanning…";
    btn.innerHTML = `<span class="icon sm">hourglass_top</span>${label}`;
  } else {
    btn.disabled = false;
    btn.innerHTML = `<span class="icon sm">sync</span>Rebuild`;
  }
}

export function startRebuildPolling() {
  if (rebuildPoller) return;
  rebuildPoller = setInterval(async () => {
    const p = await window.api.rebuildHistoryProgress();
    if (!p?.success) return;
    state.rebuild.running = !!p.running;
    state.rebuild.indexed = p.indexed || 0;
    state.rebuild.total = p.total || 0;
    updateRebuildButton();
    if (p.done || !p.running) {
      clearInterval(rebuildPoller);
      rebuildPoller = null;
      state.rebuild.running = false;
      await Promise.all([refreshHistory(), refreshStatus()]);
      rerender();
      if (p.error) alert("Rebuild failed: " + p.error);
      else alert(`Indexed ${Number(p.indexed || 0).toLocaleString()} files.`);
    }
  }, 500);
}

export async function clearHistoryConfirm() {
  if (!confirm("Clear all desktop history? Files on disk are not affected, but this PC will forget old hashes until you import history again.")) return;
  await window.api.clearHistory();
  await Promise.all([refreshHistory(), refreshStatus()]);
  rerender();
}

export async function exportHistory() {
  const res = await window.api.exportHistory();
  if (res?.success) {
    alert("History exported.");
  } else if (!res?.canceled) {
    alert("Could not export history" + (res?.error ? `: ${res.error}` : "."));
  }
}

export async function importHistory() {
  if (!confirm("Import history on this PC? This replaces the desktop transfer history/index, but does not move or delete files.")) return;
  const res = await window.api.importHistory();
  if (!res?.success) {
    if (!res?.canceled) alert("Could not import history" + (res?.error ? `: ${res.error}` : "."));
    return;
  }
  await Promise.all([refreshHistory(), refreshStatus(), refreshSettings()]);
  applyTheme();
  renderFooter();
  rerender();
  alert("History imported.");
}

export async function rebuildHistoryIndex() {
  if (state.rebuild.running) return;
  if (!confirm("Rebuild the desktop dedup index from the download folder? This hashes every file there and can take a while. You can keep using the app while it runs.")) return;
  const res = await window.api.rebuildHistoryIndex();
  if (!res?.success) {
    alert("Could not rebuild index" + (res?.error ? `: ${res.error}` : "."));
    return;
  }
  state.rebuild = { running: true, indexed: 0, total: 0 };
  updateRebuildButton();
  startRebuildPolling();
}

export async function cleanDuplicates() {
  if (state.rebuild.running) {
    alert("Wait for the rebuild to finish before cleaning duplicates.");
    return;
  }
  const probe = await window.api.removeDuplicates({ dryRun: true });
  if (!probe?.success) {
    alert("Could not scan for duplicates" + (probe?.error ? `: ${probe.error}` : "."));
    return;
  }
  if (!probe.removed) {
    alert("No duplicate files found.\n\nTip: run Rebuild first so the index matches the current folder.");
    return;
  }
  const ok = confirm(
    `Found ${Number(probe.removed).toLocaleString()} duplicate file(s) using ${fmtBytes(probe.bytesFreed)}.\n\n` +
    "Delete the extra copies, keeping one of each? This cannot be undone."
  );
  if (!ok) return;
  const res = await window.api.removeDuplicates({ dryRun: false });
  if (!res?.success) {
    alert("Could not remove duplicates" + (res?.error ? `: ${res.error}` : "."));
    return;
  }
  await Promise.all([refreshHistory(), refreshStatus()]);
  rerender();
  alert(`Removed ${Number(res.removed || 0).toLocaleString()} duplicate file(s), freed ${fmtBytes(res.bytesFreed || 0)}.`);
}
