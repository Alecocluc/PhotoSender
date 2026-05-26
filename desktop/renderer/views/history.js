import { state } from '../state.js';
import {
  escHtml, entryName, entryTime,
  fmtBytes, fmtFullTime,
  fileIcon, isVideo,
} from '../utils.js';
import { register } from '../router.js';
import { clearHistoryConfirm, exportHistory, importHistory } from '../actions.js';
import { showToast } from '../shell.js';

const historyUi = {
  query: "",
  type: "all",
  sort: "newest",
};

function selected(value, current) {
  return value === current ? "selected" : "";
}

function entryId(entry, idx) {
  return entry.id || `PH-${String(idx + 1).padStart(4, "0")}`;
}

function entryKind(entry) {
  const name = entryName(entry);
  if (isVideo(name)) return "video";
  if (/\.(heic|heif|jpe?g|png|gif|webp|tif?f|raw|nef|cr2|arw|dng)$/i.test(name || "")) return "photo";
  return "other";
}

function entrySource(entry) {
  return entry.deviceName || entry.bucketName || "Unsorted";
}

function applyHistoryFilters(items) {
  const q = historyUi.query.trim().toLowerCase();
  const filtered = items.filter((entry, idx) => {
    const kind = entryKind(entry);
    if (historyUi.type !== "all" && historyUi.type !== kind) return false;
    if (!q) return true;
    return [
      entryId(entry, idx),
      entryName(entry),
      entry.bucketName,
      entry.deviceName,
      fmtBytes(entry.size),
      kind,
    ].join(" ").toLowerCase().includes(q);
  });

  return filtered.sort((a, b) => {
    if (historyUi.sort === "oldest") return entryTime(a) - entryTime(b);
    if (historyUi.sort === "largest") return (b.size || 0) - (a.size || 0);
    if (historyUi.sort === "name") return entryName(a).localeCompare(entryName(b));
    return entryTime(b) - entryTime(a);
  });
}

function historyRow(entry, idx) {
  const name = entryName(entry);
  const ts = entryTime(entry);
  const id = entryId(entry, idx);
  const icon = fileIcon(name);
  const videoCls = isVideo(name) ? " video" : "";
  const kind = entryKind(entry);
  const source = entrySource(entry);
  return `
    <tr>
      <td><span class="id-mono">${escHtml(id)}</span></td>
      <td>
        <div class="file-cell">
          <div class="activity-thumb${videoCls}"><span class="icon sm">${icon}</span></div>
          <div style="min-width:0;">
            <div class="name" style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:320px;">${escHtml(name)}</div>
            <div class="sub">${escHtml(entry.bucketName || "")}</div>
          </div>
        </div>
      </td>
      <td><span class="source-pill">${escHtml(source)}</span></td>
      <td><span class="type-pill">${kind}</span></td>
      <td>${escHtml(fmtBytes(entry.size))}</td>
      <td><span class="mono" style="font-size:12px;color:var(--text-soft);">${escHtml(fmtFullTime(ts))}</span></td>
      <td><span class="badge"><span class="icon xs">check_circle</span>Saved</span></td>
    </tr>
  `;
}

function historyCard(entry, idx) {
  const name = entryName(entry);
  const ts = entryTime(entry);
  const icon = fileIcon(name);
  const videoCls = isVideo(name) ? " video" : "";
  const kind = entryKind(entry);
  return `
    <article class="history-card">
      <div class="activity-thumb${videoCls}"><span class="icon sm">${icon}</span></div>
      <div class="history-card-main">
        <div class="name">${escHtml(name)}</div>
        <div class="meta">${escHtml(entrySource(entry))} / ${escHtml(fmtFullTime(ts))}</div>
        <div class="history-card-tags">
          <span class="type-pill">${kind}</span>
          <span>${escHtml(fmtBytes(entry.size))}</span>
        </div>
      </div>
      <span class="badge"><span class="icon xs">check_circle</span>Saved</span>
    </article>
  `;
}

function renderHistory() {
  document.querySelector("#page-title").textContent = "History";
  document.querySelector("#page-tag").hidden = true;

  const items = state.history.items || [];
  const total = state.history.totalCount || items.length;
  const visible = applyHistoryFilters(items);
  const hasFilters = historyUi.query.trim() || historyUi.type !== "all";
  const hasMore = !!state.history.hasMore || items.length < total;
  const nextOffset = Number(state.history.nextOffset ?? items.length);

  document.querySelector("#view-root").innerHTML = `
    <div class="view-stack">
      <div class="toolbar">
        <div>
          <h2>Transfer history</h2>
          <div class="count">${items.length.toLocaleString()} loaded of ${total.toLocaleString()} total transfer${total === 1 ? "" : "s"}</div>
        </div>
        <div class="row-flex history-actions">
          <div class="search">
            <span class="icon sm" style="color:var(--text-muted);">search</span>
            <input id="history-search" type="text" placeholder="Filter by file, folder, source..." value="${escHtml(historyUi.query)}" aria-label="Filter history" />
          </div>
          <select class="input compact" id="history-type" aria-label="Filter by media type">
            <option value="all" ${selected("all", historyUi.type)}>All types</option>
            <option value="photo" ${selected("photo", historyUi.type)}>Photos</option>
            <option value="video" ${selected("video", historyUi.type)}>Videos</option>
            <option value="other" ${selected("other", historyUi.type)}>Other</option>
          </select>
          <select class="input compact" id="history-sort" aria-label="Sort history">
            <option value="newest" ${selected("newest", historyUi.sort)}>Newest first</option>
            <option value="oldest" ${selected("oldest", historyUi.sort)}>Oldest first</option>
            <option value="largest" ${selected("largest", historyUi.sort)}>Largest first</option>
            <option value="name" ${selected("name", historyUi.sort)}>Name A-Z</option>
          </select>
          <button class="btn" id="export-history-table"><span class="icon sm">download</span>Export</button>
          <button class="btn" id="import-history-table"><span class="icon sm">upload_file</span>Import</button>
          <button class="btn danger" id="clear-history-table"><span class="icon sm">delete</span>Clear</button>
        </div>
      </div>

      ${items.length === 0 ? `
        <div class="empty"><span class="icon">history</span><strong>No history yet</strong>Past transfers will appear here.</div>
      ` : `
        <div class="history-summary">
          <span>${visible.length.toLocaleString()} matching loaded transfer${visible.length === 1 ? "" : "s"}</span>
          ${hasFilters ? `<button class="link-button" id="history-clear-filters" type="button">Clear filters</button>` : ""}
        </div>
        <div class="table-wrap">
          <table class="table">
            <thead>
              <tr>
                <th>Entry</th><th>Filename</th><th>Source</th><th>Type</th><th>Size</th><th>Saved at</th><th>Status</th>
              </tr>
            </thead>
            <tbody id="history-tbody">
              ${visible.map(historyRow).join("") ||
                `<tr><td colspan="7" style="text-align:center;color:var(--text-muted);padding:32px;">No matches.</td></tr>`}
            </tbody>
          </table>
        </div>
        <div class="history-cards">
          ${visible.map(historyCard).join("") ||
            `<div class="empty compact-empty"><span class="icon">search_off</span><strong>No matches</strong>Try a different filter.</div>`}
        </div>
        ${hasMore ? `
          <div class="pagination-row">
            <button class="btn" id="load-more-history" data-offset="${nextOffset}"><span class="icon sm">expand_more</span>Load more</button>
          </div>
        ` : ""}
      `}
    </div>
  `;

  document.querySelector("#export-history-table")?.addEventListener("click", exportHistory);
  document.querySelector("#import-history-table")?.addEventListener("click", importHistory);
  document.querySelector("#clear-history-table")?.addEventListener("click", clearHistoryConfirm);
  document.querySelector("#history-search")?.addEventListener("input", (e) => {
    const caret = e.target.selectionStart ?? e.target.value.length;
    historyUi.query = e.target.value;
    renderHistory();
    const next = document.querySelector("#history-search");
    next?.focus();
    next?.setSelectionRange(caret, caret);
  });
  document.querySelector("#history-type")?.addEventListener("change", (e) => {
    historyUi.type = e.target.value;
    renderHistory();
  });
  document.querySelector("#history-sort")?.addEventListener("change", (e) => {
    historyUi.sort = e.target.value;
    renderHistory();
  });
  document.querySelector("#history-clear-filters")?.addEventListener("click", () => {
    historyUi.query = "";
    historyUi.type = "all";
    renderHistory();
  });
  document.querySelector("#load-more-history")?.addEventListener("click", loadMoreHistory);
}

async function loadMoreHistory(e) {
  const btn = e.currentTarget;
  btn.disabled = true;
  btn.innerHTML = `<span class="icon sm">hourglass_top</span>Loading`;
  try {
    const page = await window.api.getHistory({
      limit: 100,
      offset: Number(btn.dataset.offset || state.history.nextOffset || state.history.items?.length || 0),
    });
    const current = state.history.items || [];
    state.history = {
      ...page,
      items: [...current, ...(page.items || [])],
      totalCount: page.totalCount ?? state.history.totalCount ?? current.length,
      nextOffset: page.nextOffset ?? (current.length + (page.items || []).length),
      hasMore: !!page.hasMore,
    };
    renderHistory();
  } catch (err) {
    showToast("Could not load more history.", "error");
    btn.disabled = false;
    btn.innerHTML = `<span class="icon sm">expand_more</span>Load more`;
  }
}

register("history", renderHistory);
export { renderHistory };
