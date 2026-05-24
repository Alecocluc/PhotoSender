import { state } from '../state.js';
import {
  escHtml, entryName, entryTime,
  fmtBytes, fmtFullTime,
  fileIcon, isVideo,
} from '../utils.js';
import { register } from '../router.js';
import { clearHistoryConfirm, exportHistory, importHistory } from '../actions.js';

function historyRow(entry, idx) {
  const name = entryName(entry);
  const ts = entryTime(entry);
  const id = entry.id || `PS-${String(idx + 1).padStart(4, "0")}-X`;
  const icon = fileIcon(name);
  const videoCls = isVideo(name) ? " video" : "";
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
      <td>${escHtml(fmtBytes(entry.size))}</td>
      <td><span class="mono" style="font-size:12px;color:var(--text-soft);">${escHtml(fmtFullTime(ts))}</span></td>
      <td><span class="badge"><span class="icon xs">check_circle</span>Completed</span></td>
    </tr>
  `;
}

function renderHistory() {
  document.querySelector("#page-title").textContent = "History";
  document.querySelector("#page-tag").hidden = true;

  const items = state.history.items || [];
  const total = state.history.totalCount || items.length;

  document.querySelector("#view-root").innerHTML = `
    <div class="toolbar">
      <div>
        <h2>Session History</h2>
        <div class="count">${total.toLocaleString()} total transfer${total === 1 ? "" : "s"}</div>
      </div>
      <div class="row-flex">
        <div class="search">
          <span class="icon sm" style="color:var(--text-muted);">search</span>
          <input id="history-search" type="text" placeholder="Filter logs..." />
        </div>
        <button class="btn" id="export-history-table"><span class="icon sm">download</span>Export</button>
        <button class="btn" id="import-history-table"><span class="icon sm">upload_file</span>Import</button>
        <button class="btn danger" id="clear-history-table"><span class="icon sm">delete</span>Clear</button>
      </div>
    </div>
    ${items.length === 0 ? `
      <div class="empty"><span class="icon">history</span><strong>No history</strong>Past transfers will appear here.</div>
    ` : `
      <div style="border-radius: var(--radius); overflow: hidden; box-shadow: var(--shadow-sm);">
        <table class="table">
          <thead>
            <tr>
              <th>Entry ID</th><th>Filename</th><th>Size</th><th>Timestamp</th><th>Status</th>
            </tr>
          </thead>
          <tbody id="history-tbody">
            ${items.map(historyRow).join("")}
          </tbody>
        </table>
      </div>
    `}
  `;

  document.querySelector("#export-history-table")?.addEventListener("click", exportHistory);
  document.querySelector("#import-history-table")?.addEventListener("click", importHistory);
  document.querySelector("#clear-history-table")?.addEventListener("click", clearHistoryConfirm);
  document.querySelector("#history-search")?.addEventListener("input", (e) => {
    const q = e.target.value.trim().toLowerCase();
    const filtered = !q ? items : items.filter((it) =>
      (entryName(it) + " " + (it.bucketName || "") + " " + (it.deviceName || "")).toLowerCase().includes(q)
    );
    const tbody = document.querySelector("#history-tbody");
    if (tbody) {
      tbody.innerHTML = filtered.map(historyRow).join("") ||
        `<tr><td colspan="5" style="text-align:center;color:var(--text-muted);padding:32px;">No matches.</td></tr>`;
    }
  });
}

register("history", renderHistory);
export { renderHistory };
