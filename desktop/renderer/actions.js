import { state, refreshHistory, refreshStatus, refreshSettings } from './state.js';
import { fmtBytes } from './utils.js';
import { rerender } from './router.js';
import { applyTheme, renderFooter, showConfirm, showToast } from './shell.js';

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
      if (p.error) showToast("Rebuild failed: " + p.error, "error");
      else showToast(`Indexed ${Number(p.indexed || 0).toLocaleString()} files.`);
    }
  }, 500);
}

export async function clearHistoryConfirm() {
  const ok = await showConfirm({
    title: "Clear desktop history?",
    message: "Files on disk are not affected, but this PC will forget old hashes until you import history again.",
    confirmText: "Clear history",
    danger: true,
  });
  if (!ok) return;
  await window.api.clearHistory();
  await Promise.all([refreshHistory(), refreshStatus()]);
  rerender();
  showToast("Desktop history cleared.");
}

export async function exportHistory() {
  const res = await window.api.exportHistory();
  if (res?.success) {
    showToast("History exported.");
  } else if (!res?.canceled) {
    showToast("Could not export history" + (res?.error ? `: ${res.error}` : "."), "error");
  }
}

export async function importHistory() {
  const ok = await showConfirm({
    title: "Import history?",
    message: "This replaces the desktop transfer history and dedup index, but does not move or delete files.",
    confirmText: "Import",
  });
  if (!ok) return;
  const res = await window.api.importHistory();
  if (!res?.success) {
    if (!res?.canceled) showToast("Could not import history" + (res?.error ? `: ${res.error}` : "."), "error");
    return;
  }
  await Promise.all([refreshHistory(), refreshStatus(), refreshSettings()]);
  applyTheme();
  renderFooter();
  rerender();
  showToast("History imported.");
}

export async function rebuildHistoryIndex() {
  if (state.rebuild.running) return;
  const ok = await showConfirm({
    title: "Rebuild dedup index?",
    message: "This hashes every file in the download folder. You can keep using the app while it runs.",
    confirmText: "Rebuild",
  });
  if (!ok) return;
  const res = await window.api.rebuildHistoryIndex();
  if (!res?.success) {
    showToast("Could not rebuild index" + (res?.error ? `: ${res.error}` : "."), "error");
    return;
  }
  state.rebuild = { running: true, indexed: 0, total: 0 };
  updateRebuildButton();
  startRebuildPolling();
}

export async function cleanDuplicates() {
  if (state.rebuild.running) {
    showToast("Wait for the rebuild to finish before cleaning duplicates.", "error");
    return;
  }
  const probe = await window.api.removeDuplicates({ dryRun: true });
  if (!probe?.success) {
    showToast("Could not scan for duplicates" + (probe?.error ? `: ${probe.error}` : "."), "error");
    return;
  }
  if (!probe.removed) {
    showToast("No duplicate files found. Run Rebuild first if the folder changed.");
    return;
  }
  const ok = await showConfirm({
    title: "Delete duplicate files?",
    message: `Found ${Number(probe.removed).toLocaleString()} duplicate file(s) using ${fmtBytes(probe.bytesFreed)}. Extra copies will be deleted and this cannot be undone.`,
    confirmText: "Delete duplicates",
    danger: true,
  });
  if (!ok) return;
  const res = await window.api.removeDuplicates({ dryRun: false });
  if (!res?.success) {
    showToast("Could not remove duplicates" + (res?.error ? `: ${res.error}` : "."), "error");
    return;
  }
  await Promise.all([refreshHistory(), refreshStatus()]);
  rerender();
  showToast(`Removed ${Number(res.removed || 0).toLocaleString()} duplicate file(s), freed ${fmtBytes(res.bytesFreed || 0)}.`);
}
