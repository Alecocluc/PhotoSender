import { state, refreshSettings } from '../state.js';
import {
  fmtBytes, fmtSpeed, fmtUptime, escHtml,
  entryName, entryTime, fmtTime, fmtFullTime,
  fileIcon, isVideo, primaryIP,
} from '../utils.js';
import { register, rerender } from '../router.js';

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

function renderDashboard() {
  document.querySelector("#page-title").textContent = "Dashboard Overview";
  document.querySelector("#page-tag").hidden = true;

  const loading = state.initializing;
  const s = state.status || { totalReceived: 0, totalBytes: 0, recentActivity: [], uptimeMs: 0, currentSpeedBytesPerSec: 0 };
  const recent = (s.recentActivity || []).slice(0, 8);
  const ip = loading ? "—" : primaryIP(state.ips);
  const port = state.server.port || state.settings?.port || 3210;
  const speed = fmtSpeed(s.currentSpeedBytesPerSec);
  const dlPath = state.settings?.downloadPath || "";

  function skelVal(content, width = "80px") {
    return loading
      ? `<span class="skeleton" style="width:${width};border-radius:6px;">&nbsp;</span>`
      : content;
  }

  document.querySelector("#view-root").innerHTML = `
    <div class="bento">
      <section class="card hero-status">
        <div>
          <div class="title-row">
            <span class="title">System Status</span>
            <span class="status-pulse"></span>
          </div>
          <h2 class="server-on">Server is ${state.server.running ? "ON" : "OFF"}</h2>
          <div class="gateway-label"><span class="icon xs">router</span>Local Gateway</div>
          <div class="ip-row">
            <div class="ip">${loading ? `<span class="skeleton" style="width:160px;">&nbsp;</span>` : `${escHtml(ip)}${ip !== "—" ? `<span style="color:var(--text-muted);font-size:18px;">:${port}</span>` : ""}`}</div>
            <button class="btn-copy" id="copy-ip-btn" ${loading ? "disabled" : ""}><span class="icon xs">content_copy</span><span>Copy IP</span></button>
          </div>
          <div class="ip-hint">Enter this address on your mobile device</div>
        </div>
      </section>

      <section class="card save-card">
        <div class="icon-tile"><span class="icon">folder_open</span></div>
        <h3>Save Location</h3>
        <p>Incoming photos will be routed to your desktop folder.</p>
        <div class="path-row">
          <span class="path-text" title="${escHtml(dlPath)}">${escHtml(dlPath || "Not configured")}</span>
          <button class="path-edit" id="edit-path-btn" title="Change folder"><span class="icon sm">edit</span></button>
        </div>
      </section>

      <section class="card activity-feed">
        <div class="label-row">
          <span>Activity Feed</span>
          <span class="live"><span class="pulse"></span>Live</span>
        </div>
        <div class="activity-list" id="activity-list">
          ${loading
            ? Array.from({ length: 4 }, () => `
              <div class="activity-item">
                <div class="activity-thumb"><span class="icon">image</span></div>
                <div style="flex:1;min-width:0;display:flex;flex-direction:column;gap:6px;">
                  <span class="skeleton-block" style="width:60%;height:12px;"></span>
                  <span class="skeleton-block" style="width:35%;height:10px;"></span>
                </div>
              </div>`).join("")
            : recent.length === 0
              ? `<div style="color:var(--text-muted);font-size:13px;padding:20px 8px;text-align:center;">No transfers yet.</div>`
              : recent.map(activityItem).join("")}
        </div>
      </section>

      <div class="stats-row">
        <div class="card">
          <div class="stat-label">Transfer Speed</div>
          <div class="stat-value">${skelVal(`${speed.v}<span class="unit">${speed.u}</span>`, "60px")}</div>
        </div>
        <div class="card">
          <div class="stat-label">Total Received</div>
          <div class="stat-value">${skelVal(`${(s.totalReceived || 0).toLocaleString()}<span class="unit">files</span>`, "50px")}</div>
        </div>
        <div class="card primary">
          <div class="stat-label">Uptime</div>
          <div class="stat-value mono" id="uptime-display">${skelVal(fmtUptime(s.uptimeMs), "70px")}</div>
        </div>
      </div>
    </div>
  `;

  document.querySelector("#copy-ip-btn")?.addEventListener("click", async (e) => {
    const btn = e.currentTarget;
    const text = `http://${ip}:${port}`;
    try { await navigator.clipboard.writeText(text); } catch { /* ignore */ }
    btn.classList.add("copied");
    btn.querySelector("span:last-child").textContent = "Copied!";
    setTimeout(() => {
      btn.classList.remove("copied");
      btn.querySelector("span:last-child").textContent = "Copy IP";
    }, 1400);
  });

  document.querySelector("#edit-path-btn")?.addEventListener("click", async () => {
    await window.api.chooseFolder();
    await refreshSettings();
    rerender();
  });
}

register("dashboard", renderDashboard);
export { renderDashboard, activityItem };
