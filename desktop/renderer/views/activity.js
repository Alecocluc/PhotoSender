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
  document.querySelector("#page-title").textContent = "Live Intake";
  document.querySelector("#page-tag-text").textContent = "Receiver";
  document.querySelector("#page-tag").hidden = false;

  const items = state.history.items || [];
  const last = items[0];
  document.querySelector("#view-root").innerHTML = `
    <div class="bento intake-console" style="grid-template-columns: minmax(0, 1fr) 320px; grid-auto-rows: auto;">
      <section class="card activity-feed" style="grid-column: 1;">
        <div class="label-row">
          <span>Incoming Stream</span>
          <span class="live"><span class="pulse"></span>Live</span>
        </div>
        <div class="activity-list" style="max-height: none;">
          ${items.length === 0
            ? `<div class="empty"><span class="icon">move_to_inbox</span><strong>Waiting for incoming files</strong>When Android sends media, live save events appear here.</div>`
            : items.map(activityItem).join("")}
        </div>
      </section>
      <section class="card intake-side">
        <div class="label-row"><span>Receiver Diagnostics</span></div>
        <div class="diag-row"><span>Status</span><strong>${state.server.running ? "Ready" : "Offline"}</strong></div>
        <div class="diag-row"><span>Port</span><strong>${state.server.port || state.settings?.port || 3210}</strong></div>
        <div class="diag-row"><span>Last save</span><strong>${last ? escHtml(fmtFullTime(entryTime(last))) : "None"}</strong></div>
        <p>Use History for long-term browsing, filtering, export, and dedup maintenance.</p>
      </section>
    </div>
  `;
}

register("activity", renderActivityLog);
export { renderActivityLog };
