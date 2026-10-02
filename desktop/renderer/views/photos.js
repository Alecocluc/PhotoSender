import { state, loadMoreHistory } from '../state.js';
import {
  escHtml, entryTime, entryKind, fmtBytes, fmtClock, dayLabel, n, plural,
} from '../utils.js';
import { register, navigate } from '../router.js';
import { attachThumbs } from '../thumbs.js';
import { icon } from '../icons.js';
import { frameHtml, wireFrames, reuseFrames, emptyHtml } from '../components.js';
import { showToast } from '../shell.js';

/** Filters survive re-renders and trips to other views. */
const ui = { kind: "all", album: "" };
let loadingMore = false;

const KINDS = [["all", "All"], ["photo", "Photos"], ["video", "Videos"]];

function model() {
  const loading = state.initializing;
  const loaded = state.history.items || [];
  const ledgerTotal = Number(state.history.totalCount || loaded.length);
  const received = Number(state.status?.totalReceived ?? state.history.totalReceived ?? 0);
  const receivedBytes = Number(state.status?.totalBytes ?? state.history.totalBytes ?? 0);
  const hasMore = !!state.history.hasMore || loaded.length < ledgerTotal;
  // Frame numbers count down from everything ever received, like the Receiver's sheet.
  const total = Math.max(received, ledgerTotal, loaded.length);
  // The server lists only its newest `historyCap` transfers; the received totals are all time.
  const historyCap = Number(state.history.historyCap || 0);
  const capped = historyCap > 0 && ledgerTotal >= historyCap && received > ledgerTotal;

  const media = [];
  loaded.forEach((e, i) => {
    const kind = entryKind(e);
    if (kind !== "other") media.push({ e, kind, number: total - i });
  });

  const albums = [...new Set(media.map((f) => f.e.bucketName || "Unsorted"))]
    .sort((a, b) => a.localeCompare(b, undefined, { sensitivity: "base" }));
  if (ui.album && !albums.includes(ui.album)) ui.album = "";

  const shown = media.filter((f) =>
    (ui.kind === "all" || f.kind === ui.kind) && (!ui.album || (f.e.bucketName || "Unsorted") === ui.album));

  return {
    loading, loaded, ledgerTotal, received, receivedBytes, hasMore, total, capped, media, albums, shown,
    filtered: ui.kind !== "all" || !!ui.album,
  };
}

/** Day groups in ledger order (newest first). The last one may continue on the next page. */
function groupByDay(frames) {
  const groups = [];
  let current = null;
  for (const f of frames) {
    const ts = entryTime(f.e);
    const key = ts ? new Date(ts).toDateString() : "undated";
    if (!current || current.key !== key) {
      current = { key, ts, frames: [], bytes: 0 };
      groups.push(current);
    }
    current.frames.push(f);
    current.bytes += f.e.size || 0;
  }
  return groups;
}

function subLine(m) {
  if (m.loading) return "";
  if (!m.received && !m.loaded.length) return "Nothing received yet";
  // All-time totals, labelled as such: files a phone later removed with Sync still count here.
  const parts = [
    `${plural(m.received, "file")} received`,
    fmtBytes(m.receivedBytes),
  ];
  if (m.hasMore) parts.push(`Showing ${n(m.loaded.length)} of ${n(m.ledgerTotal)}`);
  return parts.join(" · ");
}

function controlsHtml(m) {
  if (m.loading || (!m.media.length && !m.filtered)) return "";
  return `
    <div class="seg" role="group" aria-label="Show">
      ${KINDS.map(([value, label]) =>
        `<button type="button" id="photos-kind-${value}" data-kind="${value}" aria-pressed="${ui.kind === value}">${label}</button>`).join("")}
    </div>
    <select class="select" id="photos-album" aria-label="Album">
      <option value=""${ui.album ? "" : " selected"}>All albums</option>
      ${m.albums.map((a) => `<option value="${escHtml(a)}"${a === ui.album ? " selected" : ""}>${escHtml(a)}</option>`).join("")}
    </select>`;
}

function loadingHtml() {
  return `<div class="sheet photos-loading" aria-busy="true" aria-label="Loading photos">${Array.from({ length: 12 }, () =>
    `<div class="frame is-loading" aria-hidden="true"><span class="edge"></span><span class="shot"></span><span class="edge"></span></div>`).join("")}</div>`;
}

function dayHtml(group, index, partial) {
  const count = group.frames.length;
  const tally = partial
    ? `${n(count)}+ ${count === 1 ? "frame" : "frames"}`
    : `${plural(count, "frame")} · ${fmtBytes(group.bytes)}`;
  const id = `photos-day-${index}`;
  return `
    <section class="photos-day" aria-labelledby="${id}">
      <header class="guide">
        <h2 class="photos-day-name" id="${id}"><strong>${escHtml(dayLabel(group.ts))}</strong></h2>
        <span${partial ? ` title="Load more to see the rest of this day"` : ""}>${escHtml(tally)}</span>
      </header>
      <div class="sheet">${group.frames.map((f) => frameHtml(f.e, {
        number: f.number,
        edgeRight: fmtClock(entryTime(f.e)),
        edgeBottom: f.e.bucketName || "",
      })).join("")}</div>
    </section>`;
}

function emptyStateHtml(m) {
  if (m.filtered) {
    const things = { photo: "photos", video: "videos", all: "photos or videos" }[ui.kind];
    const subject = ui.album ? `No ${things} from <strong>${escHtml(ui.album)}</strong>` : `No ${things}`;
    return emptyHtml({
      title: ui.kind === "all" ? "Nothing in this view" : `No ${things} in this view`,
      body: m.hasMore
        ? `${subject} among the newest ${n(m.loaded.length)} transfers. Load more to look further back.`
        : `${subject} have arrived on this computer.`,
      action: `<button type="button" class="link" id="photos-reset">Show everything</button>`,
    });
  }
  if (m.loaded.length && m.hasMore) {
    return emptyHtml({
      title: "No photos in the newest transfers",
      body: `The newest ${n(m.loaded.length)} transfers are other kinds of files. Load more to look further back.`,
    });
  }
  return emptyHtml({
    title: "No photos yet",
    body: "Photos and videos you receive from your phone appear here, grouped by day.",
    action: m.loaded.length
      ? `<button type="button" class="link" id="photos-to-history">See the other files in History</button>`
      : "",
  });
}

function footHtml(m) {
  const hint = m.shown.length
    ? `<p class="photos-hint">Click a frame to open it. Right-click to show it in its folder.</p>`
    : "";
  const capNote = m.capped && !m.hasMore
    ? `<p class="photos-hint">Pherry lists your newest ${escHtml(n(m.ledgerTotal))} transfers. Older ones aren't listed here, but their files stay in the Pherry folder.</p>`
    : "";
  const more = m.hasMore
    ? `<button type="button" class="btn" id="photos-load-more"${loadingMore ? ` aria-disabled="true" aria-busy="true"` : ""}>${loadingMore
      ? `${icon("arrows-clockwise", { size: 16, cls: "spin" })}Loading…`
      : `${icon("caret-down", { size: 16 })}Load more`}</button>`
    : "";
  return hint || more || capNote ? `<div class="photos-foot">${more}${capNote}${hint}</div>` : "";
}

function bodyHtml(m) {
  if (m.loading) return loadingHtml();
  if (!m.shown.length) return `${emptyStateHtml(m)}${footHtml(m)}`;
  const groups = groupByDay(m.shown);
  return `
    <div class="photos-days">${groups.map((g, i) => dayHtml(g, i, m.hasMore && i === groups.length - 1)).join("")}</div>
    ${footHtml(m)}`;
}

function viewHtml(m) {
  const sub = subLine(m);
  return `
    <header class="view-head">
      <div>
        <h1 class="view-title" id="photos-title">Photos</h1>
        ${sub ? `<div class="view-sub" id="photos-sub">${escHtml(sub)}</div>` : ""}
      </div>
      <div class="view-actions">
        ${controlsHtml(m)}
        <button type="button" class="btn" id="photos-open-folder">${icon("folder-open", { size: 17 })}Open folder</button>
      </div>
    </header>
    <div class="photos" id="photos-body">${bodyHtml(m)}</div>`;
}

/**
 * Where the reader is: the focused control or frame, and (for live refreshes) the first frame in
 * view, so arrivals landing above don't shove the sheet out from under them.
 */
function snapshot(keepPlace) {
  const root = document.querySelector("#view-root");
  const active = document.activeElement;
  const focus = root && active && active !== root && root.contains(active)
    ? { id: active.id || "", key: active.dataset?.key || "" }
    : null;
  let anchor = null;
  const main = document.querySelector("#main");
  if (keepPlace && main && main.scrollTop > 0) {
    const top = main.getBoundingClientRect().top + 40;
    const el = [...root.querySelectorAll(".photos-day .frame[data-key]")].find((f) => f.getBoundingClientRect().bottom > top);
    if (el) anchor = { key: el.dataset.key, offset: el.getBoundingClientRect().top - main.getBoundingClientRect().top };
  }
  return { focus, anchor };
}

function frameByKey(root, key) {
  return [...root.querySelectorAll(".frame[data-key]")].find((f) => f.dataset.key === key) || null;
}

function restore(snap) {
  const root = document.querySelector("#view-root");
  const main = document.querySelector("#main");
  if (snap.anchor && main) {
    const el = frameByKey(root, snap.anchor.key);
    if (el) {
      const delta = el.getBoundingClientRect().top - main.getBoundingClientRect().top - snap.anchor.offset;
      if (delta) main.scrollBy({ top: delta, behavior: "instant" });
    }
  }
  if (snap.focus) {
    const target = (snap.focus.id && document.getElementById(snap.focus.id))
      || (snap.focus.key && frameByKey(root, snap.focus.key));
    target?.focus({ preventScroll: true });
  }
}

function render({ keepPlace = false } = {}) {
  const root = document.querySelector("#view-root");
  if (!root) return;
  const m = model();
  const snap = snapshot(keepPlace);
  const previous = new Map();
  if (keepPlace) root.querySelectorAll(".photos-day .frame[data-key]").forEach((el) => previous.set(el.dataset.key, el));

  const scratch = document.createElement("div");
  scratch.innerHTML = viewHtml(m);
  // Wire before reusing, so frames carried over (already wired) aren't bound twice. Frames still
  // developing are carried over too: their develop clock (components.js) keeps them from replaying.
  wireFrames(scratch);
  reuseFrames(scratch, previous);
  root.replaceChildren(...scratch.childNodes);

  attachThumbs(root);
  wire();
  restore(snap);
}

function setFilter(next) {
  Object.assign(ui, next);
  render();
}

async function onLoadMore() {
  if (loadingMore) return;
  const frames = document.querySelectorAll("#photos-body .frame[data-key]");
  const lastKey = frames.length ? frames[frames.length - 1].dataset.key : "";
  loadingMore = true;
  render({ keepPlace: true });
  try {
    // Throws on an empty page (a dead receiver) and leaves the ledger untouched; arrivals that land
    // meanwhile are kept, so nothing here may put back an older copy of state.history.
    await loadMoreHistory();
  } catch {
    loadingMore = false;
    render({ keepPlace: true });
    showToast("Couldn't load older photos. Check that Pherry is receiving, then try again.", "error");
    return;
  }
  loadingMore = false;
  render({ keepPlace: true });
  // Carry keyboard focus on to the first frame that just loaded.
  const all = [...document.querySelectorAll("#photos-body .frame[data-key]")];
  const i = lastKey ? all.findIndex((f) => f.dataset.key === lastKey) : -1;
  const next = all[i + 1] || document.querySelector("#photos-load-more");
  if (document.activeElement?.id === "photos-load-more" || !document.activeElement || document.activeElement === document.body) {
    next?.focus({ preventScroll: true });
  }
}

function wire() {
  document.querySelector("#photos-open-folder")?.addEventListener("click", () => window.api.openFolder());
  document.querySelectorAll("#view-root .seg [data-kind]").forEach((b) =>
    b.addEventListener("click", () => { if (ui.kind !== b.dataset.kind) setFilter({ kind: b.dataset.kind }); }));
  document.querySelector("#photos-album")?.addEventListener("change", (e) => setFilter({ album: e.currentTarget.value }));
  document.querySelector("#photos-reset")?.addEventListener("click", () => {
    setFilter({ kind: "all", album: "" });
    document.querySelector("#photos-kind-all")?.focus();
  });
  document.querySelector("#photos-to-history")?.addEventListener("click", () => navigate("history"));
  document.querySelector("#photos-load-more")?.addEventListener("click", onLoadMore);
}

// Entering the view renders it; arrivals while it is on screen refresh it without losing the reader's place.
register("photos", () => {
  render({ keepPlace: !!document.querySelector("#photos-title") });
});
export { render as renderPhotos };
