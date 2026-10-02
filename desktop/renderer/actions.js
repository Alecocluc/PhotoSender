import { state, refreshHistory, refreshStatus, refreshSettings } from './state.js';
import { escHtml, fmtBytes, n, plural } from './utils.js';
import { rerender } from './router.js';
import { icon } from './icons.js';
import { applyTheme, renderStation, showConfirm, showToast } from './shell.js';

let rebuildPoller = null;

/** Reflects a running index rebuild in the Settings maintenance rows (no-op when Settings isn't on screen). */
export function updateRebuildButton() {
  const running = !!state.rebuild.running;
  // Read before anything is disabled or hidden: a disabled button drops keyboard focus to the page.
  const focused = document.activeElement;
  const cleanBtn = document.querySelector("#clean-duplicates");
  if (cleanBtn) {
    cleanBtn.disabled = running;
    cleanBtn.title = running ? "Wait for the rebuild to finish" : "";
  }
  const btn = document.querySelector("#rebuild-index");
  if (btn) {
    btn.disabled = running;
    btn.setAttribute("aria-busy", String(running));
    btn.innerHTML = running
      ? `${icon("arrows-clockwise", { size: 17, cls: "spin" })}<span>Rebuilding…</span>`
      : `${icon("arrows-clockwise", { size: 17 })}<span>Rebuild</span>`;
  }
  const status = document.querySelector("#rebuild-status");
  if (status) {
    const { indexed, total } = state.rebuild;
    status.hidden = !running;
    const text = !running ? "" : total > 0
      ? `Hashing ${n(indexed)} of ${n(total)}…`
      : "Finding files…";
    if (status.textContent !== text) status.textContent = text;
    // Focusable from script only, so focus can rest on the progress line while the button is disabled.
    if (status.getAttribute("tabindex") !== "-1") status.setAttribute("tabindex", "-1");
  }
  // Keep the keyboard where it was: on the progress line while the rebuild runs, back on the
  // button once it is done.
  if (running && btn && status && focused === btn) status.focus();
  else if (!running && btn && status && focused === status) btn.focus();
}

export function startRebuildPolling() {
  if (rebuildPoller) return;
  rebuildPoller = setInterval(async () => {
    let p;
    try {
      p = await window.api.rebuildHistoryProgress();
    } catch {
      return;
    }
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
      renderStation();
      rerender();
      if (p.error) showToast(`Couldn't rebuild the duplicate index: ${p.error}. Try again.`, "error");
      else showToast(`Duplicate index rebuilt from ${plural(Number(p.indexed || 0), "file")}.`);
    }
  }, 500);
}

export async function clearHistoryConfirm() {
  const ok = await showConfirm({
    title: "Clear the history on this computer?",
    message: "This removes the recent transfer log. Files, your Photos library and the duplicate index stay in place, so phones still recognise their saved copies.",
    confirmText: "Clear history",
    danger: true,
  });
  if (!ok) return;
  const res = await window.api.clearHistory();
  if (res && res.success === false) {
    showToast("Couldn't clear the history. Check that Pherry is receiving, then try again.", "error");
    return;
  }
  await Promise.all([refreshHistory(), refreshStatus()]);
  renderStation();
  rerender();
  showToast("History cleared. Your files are still in the Pherry folder.");
}

export async function exportHistory() {
  const res = await window.api.exportHistory();
  if (res?.success) {
    const file = String(res.filePath || "").split(/[\\/]/).pop();
    showToast(file ? `History exported to ${file}.` : "History exported.");
  } else if (!res?.canceled) {
    showToast(`Couldn't export the history${res?.error ? `: ${res.error}` : ""}. Try another location.`, "error");
  }
}

export async function importHistory() {
  const ok = await showConfirm({
    title: "Restore records from a history backup?",
    message: "Pherry merges records for files that are still on disk into its index. Existing records stay in place. No photos or videos are moved or deleted.",
    confirmText: "Choose a backup",
  });
  if (!ok) return;
  const res = await window.api.importHistory();
  if (!res?.success) {
    if (!res?.canceled) showToast(`Couldn't import the history${res?.error ? `: ${res.error}` : ""}.`, "error");
    return;
  }
  await Promise.all([refreshHistory(), refreshStatus(), refreshSettings()]);
  applyTheme();
  renderStation();
  rerender();
  showToast(`History imported: ${plural(Number(state.status?.totalReceived || 0), "file")} on record.`);
}

export async function rebuildHistoryIndex() {
  if (state.rebuild.running) return;
  const folder = state.settings?.downloadPath || "";
  const ok = await showConfirm({
    title: "Rebuild the duplicate index?",
    message: `Pherry reads every file in ${folder ? `<strong>${escHtml(folder)}</strong>` : "the Pherry folder"} so it can recognise copies. A large folder takes a while; you can keep using Pherry meanwhile.`,
    confirmText: "Rebuild index",
  });
  if (!ok) return;
  const res = await window.api.rebuildHistoryIndex();
  if (!res?.success) {
    showToast(`Couldn't start the rebuild${res?.error ? `: ${res.error}` : ""}. Try again.`, "error");
    return;
  }
  state.rebuild = { running: true, indexed: 0, total: 0 };
  updateRebuildButton();
  startRebuildPolling();
}

export async function cleanDuplicates() {
  if (state.rebuild.running) {
    showToast("Wait for the rebuild to finish, then look for duplicates.", "error");
    return;
  }
  const probe = await window.api.removeDuplicates({ dryRun: true });
  if (!probe?.success) {
    showToast(`Couldn't look for duplicates${probe?.error ? `: ${probe.error}` : ""}. Try again.`, "error");
    return;
  }
  const count = Number(probe.removed || 0);
  if (!count) {
    showToast("No duplicate copies found. If you moved files by hand, rebuild the duplicate index first.");
    return;
  }
  const groups = Number(probe.groups || 0);
  const copies = plural(count, "extra copy", "extra copies");
  const ok = await showConfirm({
    title: `Delete ${plural(count, "duplicate copy", "duplicate copies")}?`,
    message: `Pherry found <strong>${escHtml(copies)}</strong>${groups ? ` of ${escHtml(plural(groups, "file"))}` : ""}, using ${escHtml(fmtBytes(probe.bytesFreed || 0))}. The oldest copy within each phone's folder stays; the rest are deleted from disk. Copies belonging to other phones are kept. This can't be undone.`,
    confirmText: `Delete ${n(count)}`,
    danger: true,
  });
  if (!ok) return;
  const res = await window.api.removeDuplicates({ dryRun: false });
  if (!res?.success) {
    showToast(`Couldn't delete the duplicates${res?.error ? `: ${res.error}` : ""}. Rebuild the duplicate index, then try again.`, "error");
    return;
  }
  await Promise.all([refreshHistory(), refreshStatus()]);
  renderStation();
  rerender();
  showToast(`Deleted ${plural(Number(res.removed || 0), "duplicate copy", "duplicate copies")} and freed ${fmtBytes(res.bytesFreed || 0)}.`);
}
