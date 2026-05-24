import { state } from '../state.js';
import {
  escHtml, entryName, entryTime,
  fmtTime, fmtFullTime, fmtBytes,
  fileIcon, isVideo,
} from '../utils.js';
import { register } from '../router.js';

function activityItem(entry) {
  const name = entryName(entry);
  const icon = fileIcon(name);
  const videoCls = isVideo(name) ? " video" : "";
  const ts = entryTime(entry);
  return `
    <div class="activity-item" title="${escHtml(fmtFullTime(ts))}">
      <div class="activity-thumb${videoCls}"><span class="icon">${icon}</span></div>
      <div style="min-width:0;">
        <div class="name" style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">${escHtml(name)}</div>
        <div class="meta">${escHtml(fmtTime(ts))}${entry.size ? ` • ${escHtml(fmtBytes(entry.size))}` : ""}</div>
      </div>
      <div class="badge"><span class="icon xs">check_circle</span>Saved</div>
    </div>
  `;
}

function renderActivityLog() {
  document.querySelector("#page-title").textContent = "Activity Log";
  document.querySelector("#page-tag-text").textContent = "Live Sync";
  document.querySelector("#page-tag").hidden = false;

  const items = state.history.items || [];
  document.querySelector("#view-root").innerHTML = `
    <div class="bento" style="grid-template-columns: 1fr; grid-auto-rows: auto;">
      <section class="card activity-feed" style="grid-column: 1 / -1;">
        <div class="label-row">
          <span>Recent Transfers</span>
          <span class="live"><span class="pulse"></span>Live</span>
        </div>
        <div class="activity-list" style="max-height: none;">
          ${items.length === 0
            ? `<div class="empty"><span class="icon">inbox</span><strong>No transfers yet</strong>When your phone sends files they will appear here.</div>`
            : items.map(activityItem).join("")}
        </div>
      </section>
    </div>
  `;
}

register("activity", renderActivityLog);
export { renderActivityLog };
