import { state, loadMoreHistory } from '../state.js';
import {
  escHtml, entryName, entryTime, entryKey, entryKind, fileIcon,
  fmtBytes, fmtClock, fmtFullTime, n, plural,
} from '../utils.js';
import { register, navigate } from '../router.js';
import { clearHistoryConfirm, exportHistory, importHistory } from '../actions.js';
import { showToast } from '../shell.js';
import { attachThumbs, canThumbnail } from '../thumbs.js';
import { icon } from '../icons.js';
import { emptyHtml } from '../components.js';

/** Page size of `loadMoreHistory()` in state.js. */
const PAGE = 100;
/** Under this width the ledger rows are laid out as cards (see history.css). */
const CARD_MQ = window.matchMedia("(max-width: 820px)");
const COLLATOR = new Intl.Collator(undefined, { numeric: true, sensitivity: "base" });

const ui = { query: "", type: "all", sort: "newest", activeKey: "", loadingMore: false };

const TYPE_OPTIONS = [["all", "All types"], ["photo", "Photos"], ["video", "Videos"], ["other", "Other"]];
const SORT_OPTIONS = [["newest", "Newest first"], ["oldest", "Oldest first"], ["largest", "Largest first"], ["name", "By name"]];
const TYPE_WORDS = { photo: "photos", video: "videos", other: "other files" };

const $ = (sel) => document.querySelector(sel);

/** "Oct 2", or "Oct 2, 2024" outside the current year. */
function stampDate(ts) {
  if (!ts) return "";
  const d = new Date(ts);
  const year = d.getFullYear() === new Date().getFullYear() ? undefined : "numeric";
  return d.toLocaleDateString(undefined, { month: "short", day: "numeric", year });
}

// The oldest transfer in the ledger, for "since <date>". Known locally once every page is loaded;
// before that it is asked for once per ledger size (a one-row page at the far end).
const since = { total: -1, time: 0, pending: false };

function summary() {
  const items = state.history.items || [];
  const total = Math.max(Number(state.history.totalCount || 0), items.length);
  const hasMore = !!state.history.hasMore || items.length < total;
  const bytes = Number(state.status?.totalBytes ?? state.history.totalBytes ?? 0);
  const received = Number(state.status?.totalReceived ?? state.history.totalReceived ?? total);
  // The server keeps only its newest `historyCap` transfers, while the received totals are all time.
  const historyCap = Number(state.history.historyCap || 0);
  const capped = historyCap > 0 && total >= historyCap && received > total;
  // When capped, the oldest listed transfer isn't the first one, so there is no honest "since".
  let first = 0;
  if (capped) {
    first = 0;
  } else if (!hasMore) {
    first = items.reduce((min, e) => {
      const t = entryTime(e);
      return t && (!min || t < min) ? t : min;
    }, 0);
  } else if (since.total >= 0) {
    first = since.time;
  }
  return { loading: state.initializing, items, total, hasMore, bytes, received, capped, first };
}

function isFiltered() {
  return !!ui.query.trim() || ui.type !== "all";
}

function filterRows(rows) {
  const q = ui.query.trim().toLowerCase();
  return rows.filter((r) => {
    if (ui.type !== "all" && r.kind !== ui.type) return false;
    if (!q) return true;
    return [r.name, r.e.bucketName, r.e.deviceName, String(r.no), fmtBytes(r.e.size), r.kind]
      .join(" ").toLowerCase().includes(q);
  });
}

function sortRows(rows) {
  const by = {
    oldest: (a, b) => a.time - b.time || a.no - b.no,
    largest: (a, b) => (b.e.size || 0) - (a.e.size || 0) || b.no - a.no,
    name: (a, b) => COLLATOR.compare(a.name, b.name) || b.no - a.no,
  }[ui.sort] || ((a, b) => b.time - a.time || b.no - a.no);
  return rows.sort(by);
}

function model() {
  const s = summary();
  // Ledger numbers count up from the first transfer; items arrive newest first from offset 0.
  const rows = s.items.map((e, i) => ({
    e, no: s.total - i, name: entryName(e), time: entryTime(e), kind: entryKind(e), key: entryKey(e),
  }));
  return { ...s, rows, visible: sortRows(filterRows(rows)), filtered: isFiltered() };
}

function subText(m) {
  if (m.loading) return "Loading…";
  if (!m.total) return "Nothing received yet";
  const since = m.first ? ` · since ${stampDate(m.first)}` : "";
  // Bytes are all time; when the ledger lists fewer transfers than were received, say so.
  if (m.received > m.total) {
    return `${plural(m.total, m.capped ? "recent transfer" : "transfer")} · All time ${plural(m.received, "file")} · ${fmtBytes(m.bytes)}${since}`;
  }
  return `${plural(m.total, "transfer")} · ${fmtBytes(m.bytes)}${since}`;
}

function countText(m) {
  if (m.filtered) return `${n(m.visible.length)} matching${m.hasMore ? ` in ${n(m.rows.length)} loaded` : ""}`;
  return m.hasMore ? `Showing ${n(m.rows.length)} of ${n(m.total)}` : `Showing all ${n(m.total)}`;
}

function optionsHtml(options, current) {
  return options.map(([value, label]) =>
    `<option value="${value}"${value === current ? " selected" : ""}>${label}</option>`).join("");
}

function actionsHtml(m) {
  if (m.loading) return "";
  const has = m.rows.length > 0;
  const filters = has ? `
    <div class="search history-search" role="search">
      ${icon("magnifying-glass", { size: 17 })}
      <input id="history-search" type="text" value="${escHtml(ui.query)}" placeholder="Search file, album or phone"
        aria-label="Search the history by file, album or phone" autocomplete="off" spellcheck="false" />
      <button class="history-search-clear" id="history-search-clear" type="button" aria-label="Clear search" title="Clear search"${ui.query ? "" : " hidden"}>${icon("x", { size: 14, weight: "bold" })}</button>
    </div>
    <select class="select" id="history-type" aria-label="File type">${optionsHtml(TYPE_OPTIONS, ui.type)}</select>
    <select class="select" id="history-sort" aria-label="Sort order">${optionsHtml(SORT_OPTIONS, ui.sort)}</select>` : "";
  return `
    <div class="view-actions">
      ${filters}
      <div class="history-tools${has ? "" : " alone"}" role="group" aria-label="History file">
        ${has ? `<button class="btn quiet" id="history-export" title="Save this history to a file">${icon("download-simple", { size: 17 })}Export</button>` : ""}
        <button class="btn quiet" id="history-import" title="Replace this history with one you exported">${icon("upload-simple", { size: 17 })}Import</button>
        ${has ? `<button class="btn danger" id="history-clear" title="Clear the history on this computer">${icon("trash", { size: 17 })}Clear</button>` : ""}
      </div>
    </div>`;
}

function typeTag(r) {
  if (r.kind === "photo") return `<span class="tag">Photo</span>`;
  if (r.kind === "video") return `<span class="tag">Video</span>`;
  const ext = /\.([a-z0-9]{1,6})$/i.exec(r.name)?.[1];
  return `<span class="tag quiet">${escHtml(ext || "File")}</span>`;
}

function rowHtml(r, active) {
  const { e, name } = r;
  const tab = active ? "0" : "-1";
  const thumbable = canThumbnail(name);
  const thumb = thumbable
    ? ` data-thumb-bucket="${escHtml(e.bucketName || "")}" data-thumb-name="${escHtml(name)}"`
    : "";
  const kindWord = r.kind === "photo" ? "Photo" : r.kind === "video" ? "Video" : "File";
  return `
    <tr class="history-row" tabindex="${tab}" data-key="${escHtml(r.key)}" data-bucket="${escHtml(e.bucketName || "")}" data-name="${escHtml(name)}" data-time="${r.time}">
      <td class="c-no mono">${n(r.no)}</td>
      <td class="c-file">
        <div class="file">
          <span class="mini" aria-hidden="true"><span class="shot${thumbable ? "" : " no-thumb"}"${thumb}>${icon(fileIcon(name), { size: 16 })}</span></span>
          <span class="file-text">
            <span class="file-name" title="${escHtml(name)}">${escHtml(name)}</span>
            <span class="file-album">${escHtml(e.bucketName || "Unsorted")}<span class="file-kind"> · ${kindWord}</span><span class="file-from"> · ${escHtml(e.deviceName || "Unknown")}</span></span>
          </span>
        </div>
      </td>
      <td class="c-from">${e.deviceName ? `<span title="${escHtml(e.deviceName)}">${escHtml(e.deviceName)}</span>` : `<span class="unknown">Unknown</span>`}</td>
      <td class="c-type">${typeTag(r)}</td>
      <td class="c-size mono">${escHtml(fmtBytes(e.size))}</td>
      <td class="c-saved mono" title="${escHtml(fmtFullTime(r.time))}"><span>${escHtml(stampDate(r.time))}</span> <span class="dim">${escHtml(fmtClock(r.time))}</span></td>
      <td class="c-act">
        <div class="row-actions">
          <button class="btn sm quiet icon-only" data-act="open" tabindex="${tab}" title="Open" aria-label="Open ${escHtml(name)}">${icon("arrow-square-out", { size: 16 })}</button>
          <button class="btn sm quiet icon-only" data-act="reveal" tabindex="${tab}" title="Show in folder" aria-label="Show ${escHtml(name)} in its folder">${icon("folder-open", { size: 16 })}</button>
        </div>
      </td>
    </tr>`;
}

function tableHtml(m) {
  const activeIdx = Math.max(0, m.visible.findIndex((r) => r.key === ui.activeKey));
  return `
    <table class="ledger" aria-describedby="history-keys">
      <caption class="visually-hidden">Files received by this computer</caption>
      <thead>
        <tr>
          <th scope="col" class="c-no">No.</th>
          <th scope="col" class="c-file">File</th>
          <th scope="col" class="c-from">From</th>
          <th scope="col" class="c-type">Type</th>
          <th scope="col" class="c-size">Size</th>
          <th scope="col" class="c-saved">Saved</th>
          <th scope="col" class="c-act"><span class="visually-hidden">Actions</span></th>
        </tr>
      </thead>
      <tbody>${m.visible.map((r, i) => rowHtml(r, i === activeIdx)).join("")}</tbody>
    </table>
    <p class="visually-hidden" id="history-keys">Up and down arrows move between rows. Enter opens the file. The context menu key shows it in its folder.</p>`;
}

function bodyHtml(m) {
  if (m.loading) {
    return `<div class="ledger-loading" aria-busy="true" aria-label="Loading the history">${Array.from({ length: 8 }, () =>
      `<div class="ledger-skel"><span class="skel-thumb"></span><span class="skel-line"></span><span class="skel-line short"></span></div>`).join("")}</div>`;
  }
  if (!m.rows.length) {
    return emptyHtml({
      title: "No transfers yet",
      body: "Every file a phone sends to this computer is listed here, with its album, the phone it came from and when it was saved. Moved to a new computer? Import the history you exported from the old one.",
      action: `<button class="btn primary" data-go="receiver">Pair a phone</button>`,
    });
  }
  if (!m.visible.length) {
    const q = ui.query.trim();
    return emptyHtml({
      title: q ? `Nothing matches “${q}”` : `No ${TYPE_WORDS[ui.type] || "files"} in this list`,
      body: m.hasMore
        ? `Only the ${n(m.rows.length)} most recent transfers are loaded. Load more to look further back, or clear the filters.`
        : "Try another name, album or phone, or clear the filters.",
      action: `<button class="btn" data-clear-filters>Clear filters</button>`,
    });
  }
  return tableHtml(m);
}

function moreHtml(m) {
  if (m.loading || !m.rows.length) return "";
  if (m.capped && !m.hasMore) {
    return `
    <div class="history-more">
      <span class="history-more-note">Pherry lists the newest ${escHtml(n(m.total))} transfers. Older ones aren't listed here, but their files stay in the Pherry folder.</span>
    </div>`;
  }
  if (!m.hasMore) return "";
  const next = Math.min(PAGE, m.total - m.rows.length);
  const label = ui.loadingMore
    ? `${icon("arrows-clockwise", { size: 16, cls: "spin" })}<span>Loading…</span>`
    : `${icon("caret-down", { size: 16 })}<span>${next > 0 ? `Load ${n(next)} more` : "Load more"}</span>`;
  return `
    <div class="history-more">
      <button class="btn" id="history-load-more"${ui.loadingMore ? ` aria-disabled="true" aria-busy="true"` : ""}>${label}</button>
      <span class="history-more-count mono">Showing ${n(m.rows.length)} of ${n(m.total)}</span>
    </div>`;
}

function guideHtml(m) {
  if (!m.rows.length) return "";
  return `
    <div class="guide history-guide">
      <span class="history-span" id="history-span"></span>
      <span class="history-count">
        <span id="history-count-text" aria-live="${m.filtered ? "polite" : "off"}">${escHtml(countText(m))}</span>
        <button class="link" id="history-clear-filters" data-clear-filters${m.filtered ? "" : " hidden"}>Clear filters</button>
      </span>
    </div>`;
}

/** Everything the full render decides and a patch can't change. */
function signature(m) {
  return `${m.loading}|${m.rows.length > 0}`;
}
let lastSig = "";

function renderFull(m) {
  const root = $("#view-root");
  if (!root) return;
  lastSig = signature(m);
  root.innerHTML = `
    <header class="view-head history-head">
      <div>
        <h1 class="view-title" id="history-title">History</h1>
        <div class="view-sub" id="history-sub">${escHtml(subText(m))}</div>
      </div>
      ${actionsHtml(m)}
    </header>
    <section class="history" id="history" aria-labelledby="history-title">
      ${guideHtml(m)}
      <div id="history-body">${bodyHtml(m)}</div>
      <div id="history-more-slot">${moreHtml(m)}</div>
    </section>`;
  wireHead();
  wireSection();
  afterBody();
}

/** Live refresh that keeps the header (and the search caret) untouched. */
function patch(m) {
  const focus = captureFocus();
  $("#history-sub").textContent = subText(m);
  const count = $("#history-count-text");
  if (count) {
    count.setAttribute("aria-live", m.filtered ? "polite" : "off");
    count.textContent = countText(m);
  }
  const clear = $("#history-clear-filters");
  if (clear) clear.hidden = !m.filtered;
  $("#history-body").innerHTML = bodyHtml(m);
  $("#history-more-slot").innerHTML = moreHtml(m);
  afterBody();
  restoreFocus(focus);
}

function render() {
  const m = model();
  if ($("#history-title") && signature(m) === lastSig) patch(m);
  else renderFull(m);
  refreshSince(m);
}

function afterBody() {
  const body = $("#history-body");
  if (body) attachThumbs(body);
  updateGuide();
}

function refreshSince(m) {
  if (m.loading || !m.hasMore || since.pending || since.total === m.total) return;
  const want = m.total;
  since.pending = true;
  window.api.getHistory({ limit: 1, offset: want - 1 })
    .then((page) => { since.time = entryTime(page?.items?.[0]); })
    .catch(() => { since.time = 0; })
    .finally(() => {
      since.total = want;
      since.pending = false;
      const sub = $("#history-sub");
      if (sub && state.view === "history") sub.textContent = subText(summary());
    });
}

// ── Focus across re-renders ──────────────────────────────────────────────

function captureFocus() {
  const a = document.activeElement;
  if (!a || a === document.body) return null;
  if ($("#history-more-slot")?.contains(a)) return { more: true };
  const row = $("#history-body")?.contains(a) ? a.closest(".history-row") : null;
  if (row) return { key: row.dataset.key, act: a.dataset.act || "" };
  if (a.matches("#history-body [data-clear-filters]")) return { clearFilters: true };
  return null;
}

function rowByKey(key) {
  return [...document.querySelectorAll("#history-body .history-row")].find((r) => r.dataset.key === key) || null;
}

function restoreFocus(f) {
  if (!f) return;
  if (f.more) {
    const btn = $("#history-load-more");
    if (btn) btn.focus({ preventScroll: true });
    else document.querySelector("#history-body .history-row:last-of-type")?.focus({ preventScroll: true });
    return;
  }
  if (f.clearFilters) {
    ($("#history-body [data-clear-filters]") || $("#history-search"))?.focus({ preventScroll: true });
    return;
  }
  const row = rowByKey(f.key);
  if (!row) return;
  const target = f.act ? row.querySelector(`[data-act="${f.act}"]`) : row;
  (target || row).focus({ preventScroll: true });
}

/** Roving tabindex: only the active row and its buttons sit in the Tab order. */
function setActiveRow(row) {
  if (!row || row.tabIndex === 0) {
    if (row) ui.activeKey = row.dataset.key;
    return;
  }
  document.querySelectorAll("#history-body .history-row[tabindex='0']").forEach((r) => {
    r.tabIndex = -1;
    r.querySelectorAll("[data-act]").forEach((b) => { b.tabIndex = -1; });
  });
  row.tabIndex = 0;
  row.querySelectorAll("[data-act]").forEach((b) => { b.tabIndex = 0; });
  ui.activeKey = row.dataset.key;
}

function moveTo(row) {
  if (!row) return;
  row.focus({ preventScroll: true });
  row.scrollIntoView({ block: "nearest" });
}

// ── Guide words: the date span of the rows on screen ─────────────────────

function updateGuide() {
  const out = $("#history-span");
  if (!out) return;
  const main = $("#main");
  const rows = document.querySelectorAll("#history-body .history-row");
  let html = "";
  if (main && rows.length) {
    const view = main.getBoundingClientRect();
    const head = CARD_MQ.matches ? null : $("#history-body thead");
    const top = Math.max(view.top, $(".history-guide")?.getBoundingClientRect().bottom ?? 0, head?.getBoundingClientRect().bottom ?? 0);
    // Rows are in document order, so the first one below the sticky headers is a binary search away.
    let lo = 0, hi = rows.length - 1, first = rows.length;
    while (lo <= hi) {
      const mid = (lo + hi) >> 1;
      if (rows[mid].getBoundingClientRect().bottom > top) { first = mid; hi = mid - 1; } else lo = mid + 1;
    }
    let min = 0, max = 0;
    for (let i = first; i < rows.length; i++) {
      if (rows[i].getBoundingClientRect().top >= view.bottom) break;
      const t = Number(rows[i].dataset.time) || 0;
      if (!t) continue;
      if (!min || t < min) min = t;
      if (t > max) max = t;
    }
    if (max) {
      const [a, b] = ui.sort === "oldest" ? [stampDate(min), stampDate(max)] : [stampDate(max), stampDate(min)];
      html = a === b
        ? `<strong>${escHtml(a)}</strong>`
        : `<strong>${escHtml(a)}</strong><span class="sep" aria-hidden="true">—</span><strong>${escHtml(b)}</strong>`;
    }
  }
  if (out.dataset.html !== html) {
    out.innerHTML = html;
    out.dataset.html = html;
  }
}

let guideFrame = 0;
function scheduleGuide() {
  if (guideFrame) return;
  guideFrame = requestAnimationFrame(() => {
    guideFrame = 0;
    if (state.view === "history") updateGuide();
  });
}
$("#main")?.addEventListener("scroll", scheduleGuide, { passive: true });
window.addEventListener("resize", scheduleGuide);

// ── Wiring ───────────────────────────────────────────────────────────────

function clearFilters() {
  ui.query = "";
  ui.type = "all";
  const search = $("#history-search");
  if (search) search.value = "";
  const type = $("#history-type");
  if (type) type.value = "all";
  const x = $("#history-search-clear");
  if (x) x.hidden = true;
  render();
  $("#history-search")?.focus();
}

function wireHead() {
  const search = $("#history-search");
  const clearBtn = $("#history-search-clear");
  search?.addEventListener("input", () => {
    ui.query = search.value;
    if (clearBtn) clearBtn.hidden = !ui.query;
    render();
  });
  search?.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && search.value) {
      e.preventDefault();
      search.value = "";
      ui.query = "";
      if (clearBtn) clearBtn.hidden = true;
      render();
    } else if (e.key === "ArrowDown") {
      const row = document.querySelector("#history-body .history-row[tabindex='0']");
      if (row) { e.preventDefault(); moveTo(row); }
    }
  });
  clearBtn?.addEventListener("click", () => {
    ui.query = "";
    if (search) { search.value = ""; search.focus(); }
    clearBtn.hidden = true;
    render();
  });
  $("#history-type")?.addEventListener("change", (e) => { ui.type = e.target.value; render(); });
  $("#history-sort")?.addEventListener("change", (e) => { ui.sort = e.target.value; render(); });
  $("#history-export")?.addEventListener("click", exportHistory);
  $("#history-import")?.addEventListener("click", importHistory);
  $("#history-clear")?.addEventListener("click", clearHistoryConfirm);
}

async function openRow(row, reveal = false) {
  const args = { bucket: row.dataset.bucket, name: row.dataset.name };
  let ok = false;
  try {
    ok = reveal ? await window.api.revealFile(args) : await window.api.openFile(args);
  } catch { ok = false; }
  if (!ok) showToast("That file is no longer in the Pherry folder.", "error");
}

async function onLoadMore() {
  if (ui.loadingMore) return;
  ui.loadingMore = true;
  render();
  try {
    await loadMoreHistory();
  } catch {
    showToast("Couldn't load older transfers. Check that Pherry is receiving, then try again.", "error");
  } finally {
    ui.loadingMore = false;
  }
  if (state.view === "history") render();
}

/** Delegated on the section, so it survives every patch of the rows. */
function wireSection() {
  const section = $("#history");
  if (!section) return;
  section.addEventListener("click", (e) => {
    const t = e.target;
    if (t.closest("[data-clear-filters]")) return clearFilters();
    const go = t.closest("[data-go]");
    if (go) return navigate(go.dataset.go);
    if (t.closest("#history-load-more")) return onLoadMore();
    const row = t.closest(".history-row");
    if (!row) return;
    const act = t.closest("[data-act]");
    if (act) return openRow(row, act.dataset.act === "reveal");
    // Cards read as single targets; table rows open on double-click like a file list.
    if (CARD_MQ.matches) openRow(row);
  });
  section.addEventListener("dblclick", (e) => {
    const row = e.target.closest(".history-row");
    if (!row || e.target.closest("button") || CARD_MQ.matches) return;
    openRow(row);
  });
  section.addEventListener("contextmenu", (e) => {
    const row = e.target.closest(".history-row");
    if (!row) return;
    e.preventDefault();
    openRow(row, true);
  });
  section.addEventListener("focusin", (e) => setActiveRow(e.target.closest?.(".history-row")));
  section.addEventListener("keydown", (e) => {
    const row = e.target.closest?.(".history-row");
    if (!row || e.target !== row || e.altKey || e.ctrlKey || e.metaKey) return;
    const rows = () => [...document.querySelectorAll("#history-body .history-row")];
    switch (e.key) {
      case "Enter": openRow(row); break;
      case "ArrowDown": moveTo(row.nextElementSibling); break;
      case "ArrowUp": moveTo(row.previousElementSibling); break;
      case "Home": moveTo(rows()[0]); break;
      case "End": moveTo(rows().at(-1)); break;
      default: return;
    }
    e.preventDefault();
  });
}

// Arrivals re-render this view (debounced in app.js); once on screen it patches in place.
register("history", render);
export { render as renderHistory };
