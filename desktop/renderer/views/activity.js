import { state } from '../state.js';
import {
  escHtml, entryName, entryTime,
  fmtTime, fmtFullTime, fmtBytes, fmtSpeed,
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
        <div class="meta">${escHtml(fmtTime(ts))}${entry.size ? ` / ${escHtml(fmtBytes(entry.size))}` : ""}</div>
      </div>
      <div class="badge transferring"><span class="icon xs">directions_boat</span>Arrived</div>
    </div>
  `;
}

function renderActivityLog() {
  document.querySelector("#page-title").textContent = "Transfers";
  document.querySelector("#page-tag-text").textContent = "Receiver";
  document.querySelector("#page-tag").hidden = false;

  const items = state.history.items || [];
  const statusRecent = state.status?.recentActivity || [];
  const recent = (statusRecent.length ? statusRecent : items).slice(0, 60);
  const last = recent[0];
  const speed = fmtSpeed(state.status?.currentSpeedBytesPerSec || 0);

  document.querySelector("#view-root").innerHTML = `
    <div class="view-stack">
      <section class="card live-hero">
        <div>
          <div class="label-row" style="margin-bottom:10px;"><span>Transit board</span><span class="live"><span class="pulse"></span>${state.server.running ? "Listening" : "Offline"}</span></div>
          <h2>${state.server.running ? "Ready for incoming media" : "Receiver is offline"}</h2>
          <p>Incoming photos and videos appear here as soon as the desktop writes them to disk.</p>
        </div>
        <div class="live-stats">
          <div class="live-stat"><span>Current speed</span><strong>${speed.v} ${speed.u}</strong></div>
          <div class="live-stat"><span>Loaded events</span><strong>${recent.length.toLocaleString()}</strong></div>
        </div>
      </section>

      <div class="split-grid">
        <section class="card activity-feed">
          <div class="label-row">
            <span>Incoming stream</span>
            <span class="live"><span class="pulse"></span>Live</span>
          </div>
          <div class="activity-list" style="max-height:none;">
            ${recent.length === 0
              ? `<div class="empty"><span class="icon">move_to_inbox</span><strong>Waiting for files</strong>When Android sends media, save events appear here.</div>`
              : recent.map(activityItem).join("")}
          </div>
        </section>

        <section class="card intake-side">
          <div class="label-row"><span>Receiver diagnostics</span></div>
          <div class="diag-row"><span>Status</span><strong>${state.server.running ? "Ready" : "Offline"}</strong></div>
          <div class="diag-row"><span>Port</span><strong>${state.server.port || state.settings?.port || 3210}</strong></div>
          <div class="diag-row"><span>Last save</span><strong>${last ? escHtml(fmtFullTime(entryTime(last))) : "None"}</strong></div>
          <div class="diag-row"><span>Total received</span><strong>${(state.status?.totalReceived || 0).toLocaleString()}</strong></div>
          <p>History is the long-term ledger for filtering, export, import, and dedup maintenance.</p>
        </section>
      </div>
    </div>
  `;
}

register("activity", renderActivityLog);
export { renderActivityLog };
