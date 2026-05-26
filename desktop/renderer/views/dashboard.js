import { state, refreshSettings } from '../state.js';
import {
  fmtBytes, fmtSpeed, fmtUptime, escHtml,
  entryName, entryTime, fmtTime, fmtFullTime,
  fileIcon, isVideo, primaryIP,
} from '../utils.js';
import { register, rerender } from '../router.js';
import { renderQrCode } from '../qr.js';

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
      <div class="badge"><span class="icon xs">check_circle</span>Saved</div>
    </div>
  `;
}

function renderDashboard() {
  document.querySelector("#page-title").textContent = "Receiver";
  document.querySelector("#page-tag").hidden = true;

  const loading = state.initializing;
  const s = state.status || { totalReceived: 0, totalBytes: 0, recentActivity: [], uptimeMs: 0, currentSpeedBytesPerSec: 0 };
  const statusRecent = s.recentActivity || [];
  const recent = (statusRecent.length ? statusRecent : (state.history.items || [])).slice(0, 8);
  const ip = loading ? "-" : primaryIP(state.ips);
  const port = state.server.port || state.settings?.port || 3210;
  const address = ip !== "-" ? `http://${ip}:${port}` : "";
  const speed = fmtSpeed(s.currentSpeedBytesPerSec);
  const dlPath = state.settings?.downloadPath || "";
  const railItems = recent.slice(0, 5);

  function skelVal(content, width = "80px") {
    return loading
      ? `<span class="skeleton" style="width:${width};border-radius:6px;">&nbsp;</span>`
      : content;
  }

  document.querySelector("#view-root").innerHTML = `
    <div class="receiver-console">
      <section class="card pairing-hero">
        <div class="pairing-copy">
          <div class="title-row">
            <span class="title">Pherry Desktop</span>
            <span class="status-pulse ${state.server.running ? "" : "off"}"></span>
          </div>
          <h2 class="server-on">${state.server.running ? "Waiting for your phone" : "Receiver offline"}</h2>
          <div class="gateway-label"><span class="icon xs">qr_code_2</span>Pairing address</div>
          <div class="address-row">
            <div class="pair-address">${loading ? `<span class="skeleton" style="width:260px;">&nbsp;</span>` : escHtml(address || "No network interface")}</div>
            <button class="btn-copy" id="copy-ip-btn" ${loading || !address ? "disabled" : ""} aria-label="Copy pairing address"><span class="icon xs">content_copy</span><span>Copy address</span></button>
          </div>
          <p class="copy-note">Scan the code from Pherry on Android. Transfers stay on this local network and arrive in the folder below.</p>
          <div class="pair-steps" aria-label="Pairing steps">
            <div class="pair-step"><span class="icon sm">desktop_windows</span><span>Keep this receiver open</span></div>
            <div class="pair-step"><span class="icon sm">qr_code_scanner</span><span>Scan from Android</span></div>
            <div class="pair-step"><span class="icon sm">directions_boat</span><span>Files ferry in full quality</span></div>
          </div>
        </div>
        <div class="qr-panel" aria-label="Pairing QR code">
          ${loading || !address ? `<div class="qr-placeholder"><span class="icon">wifi_off</span><span>Waiting for network</span></div>` : `<canvas id="pair-qr" aria-hidden="true"></canvas>`}
          <div class="qr-caption">Android > Connect desktop > Scan QR</div>
        </div>
      </section>

      <section class="card save-card">
        <div class="icon-tile"><span class="icon">folder_open</span></div>
        <h3>Save Location</h3>
        <p>Incoming photos and videos are written here as originals.</p>
        <div class="path-row">
          <span class="path-text" title="${escHtml(dlPath)}">${escHtml(dlPath || "Not configured")}</span>
          <button class="path-edit" id="edit-path-btn" title="Change folder" aria-label="Change download folder"><span class="icon sm">edit</span></button>
        </div>
      </section>

      <section class="card transfer-rail">
        <div class="label-row">
          <span>Live Queue</span>
          <span class="live"><span class="pulse"></span>${state.server.running ? "Ready" : "Offline"}</span>
        </div>
        <div class="rail-list">
          ${railItems.length === 0
            ? `<div class="rail-empty"><span class="icon">move_to_inbox</span><span>No files in transit</span></div>`
            : railItems.map(activityItem).join("")}
        </div>
      </section>

      <section class="card activity-feed">
        <div class="label-row">
          <span>Recent Arrivals</span>
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
              ? `<div class="empty compact-empty"><span class="icon">qr_code_scanner</span><strong>Ready to pair</strong>Open Pherry on Android and scan the receiver code.</div>`
              : recent.map(activityItem).join("")}
        </div>
      </section>

      <div class="stats-row">
        <div class="card">
          <div class="stat-label">Transfer speed</div>
          <div class="stat-value">${skelVal(`${speed.v}<span class="unit">${speed.u}</span>`, "60px")}</div>
        </div>
        <div class="card">
          <div class="stat-label">Received</div>
          <div class="stat-value">${skelVal(`${(s.totalReceived || 0).toLocaleString()}<span class="unit">files</span>`, "50px")}</div>
          <div class="stat-foot">${skelVal(fmtBytes(s.totalBytes || 0), "70px")} stored</div>
        </div>
        <div class="card primary">
          <div class="stat-label">Uptime</div>
          <div class="stat-value mono" id="uptime-display">${skelVal(fmtUptime(s.uptimeMs), "70px")}</div>
        </div>
      </div>
    </div>
  `;

  if (address) {
    const canvas = document.querySelector("#pair-qr");
    if (canvas) renderQrCode(canvas, address);
  }

  document.querySelector("#copy-ip-btn")?.addEventListener("click", async (e) => {
    const btn = e.currentTarget;
    try { await navigator.clipboard.writeText(address); } catch { /* ignore */ }
    btn.classList.add("copied");
    btn.querySelector("span:last-child").textContent = "Copied";
    setTimeout(() => {
      btn.classList.remove("copied");
      btn.querySelector("span:last-child").textContent = "Copy address";
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
