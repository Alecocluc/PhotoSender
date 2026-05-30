import { state, refreshSettings } from '../state.js';
import { escHtml } from '../utils.js';
import { register, rerender } from '../router.js';
import {
  exportHistory, importHistory,
  rebuildHistoryIndex, cleanDuplicates,
  updateRebuildButton, startRebuildPolling,
} from '../actions.js';
import { applyTheme, renderFooter, showToast } from '../shell.js';

function renderSettings() {
  document.querySelector("#page-title").textContent = "Settings";
  document.querySelector("#page-tag").hidden = true;

  const s = state.settings || {};
  document.querySelector("#view-root").innerHTML = `
    ${state.server.error ? `<div class="banner error"><span class="icon">error</span>Server failed to start: ${escHtml(state.server.error)}</div>` : ""}
    <div class="settings-grid view-stack">
      <section class="card">
        <div class="label-row"><span>Appearance</span></div>
        <div class="form-row">
          <label>Theme</label>
          <div class="seg" role="tablist" aria-label="Theme">
            ${themeTab("system", "System", s.theme || "system")}
            ${themeTab("light", "Light", s.theme || "system")}
            ${themeTab("dark", "Dark", s.theme || "system")}
          </div>
          <div class="help">Pherry uses a fixed teal/slate palette so pairing looks consistent across devices.</div>
        </div>
      </section>

      <section class="card">
        <div class="label-row"><span>Receiver</span></div>
        <div class="setting-list">
          <div class="setting-item">
            <div class="setting-item-main">
              <strong>Download folder</strong>
              <span>Where incoming originals are saved.</span>
            </div>
            <button class="btn" id="choose-folder"><span class="icon sm">folder_open</span>Browse</button>
          </div>
          <input class="input" id="dl-path" value="${escHtml(s.downloadPath || "")}" readonly />

          <div class="setting-item">
            <div class="setting-item-main">
              <strong>Receiver port</strong>
              <span>Valid range is 1024-65535. Changing it restarts the receiver.</span>
            </div>
            <div class="row-flex">
              <input class="input" id="port-input" type="number" min="1024" max="65535" value="${escHtml(s.port || 3210)}" style="max-width:150px;" />
              <button class="btn primary" id="apply-port">Apply</button>
            </div>
          </div>

          <div class="form-row" style="margin-bottom:0;">
            <label>Available addresses</label>
            <div class="ips" id="settings-ips"></div>
          </div>
        </div>
      </section>

      <section class="card">
        <div class="label-row"><span>Pairing & security</span></div>
        <div class="setting-list">
          <div class="setting-item">
            <div class="setting-item-main">
              <strong>Pairing code</strong>
              <span>Required on the phone to remove files from this PC. Embedded in the QR code; share manually only if you trust the device.</span>
            </div>
            <div class="row-flex">
              <span class="pairing-code">${escHtml(s.pairingToken || "—")}</span>
              <button class="btn" id="rotate-token" title="Generate a new code and revoke the old one"><span class="icon sm">autorenew</span>Rotate</button>
            </div>
          </div>
        </div>
      </section>

      <section class="card">
        <div class="label-row"><span>Behavior</span></div>
        <div class="setting-list">
          <label class="setting-item switch">
            <span class="setting-item-main"><strong>Auto-open folder on transfer</strong><span>Open the saved folder after each incoming file.</span></span>
            <input type="checkbox" id="opt-auto-open" ${s.autoOpenFolder ? "checked" : ""} /><span class="track"></span>
          </label>
          <label class="setting-item switch">
            <span class="setting-item-main"><strong>Notify on arrival</strong><span>Show a desktop notification when a new file is received.</span></span>
            <input type="checkbox" id="opt-notify" ${s.notifyOnArrival !== false ? "checked" : ""} /><span class="track"></span>
          </label>
          <label class="setting-item switch">
            <span class="setting-item-main"><strong>Keep running in tray</strong><span>Closing the window keeps the receiver running in the system tray.</span></span>
            <input type="checkbox" id="opt-tray" ${s.minimizeToTray !== false ? "checked" : ""} /><span class="track"></span>
          </label>
          <label class="setting-item switch">
            <span class="setting-item-main"><strong>Launch at startup</strong><span>Start Pherry Desktop when you sign in.</span></span>
            <input type="checkbox" id="opt-launch" ${s.launchAtStartup ? "checked" : ""} /><span class="track"></span>
          </label>
        </div>
      </section>

      <section class="card">
        <div class="label-row"><span>History maintenance</span></div>
        <div class="setting-list">
          <div class="setting-item">
            <div class="setting-item-main">
              <strong>History backup</strong>
              <span>Export or import the desktop dedup ledger.</span>
            </div>
            <div class="row-flex">
              <button class="btn" id="export-history-settings"><span class="icon sm">download</span>Export</button>
              <button class="btn" id="import-history-settings"><span class="icon sm">upload_file</span>Import</button>
            </div>
          </div>
          <div class="setting-item">
            <div class="setting-item-main">
              <strong>Dedup index</strong>
              <span>Rebuild before cleaning duplicates after moving folders or drives.</span>
            </div>
            <div class="row-flex">
              <button class="btn" id="rebuild-index-settings"><span class="icon sm">sync</span>Rebuild</button>
              <button class="btn danger" id="clean-duplicates-settings"><span class="icon sm">delete_sweep</span>Clean</button>
            </div>
          </div>
        </div>
      </section>

      <section class="card about-panel">
        <div class="about-mark" aria-hidden="true">
          <svg width="30" height="30" viewBox="26 26 56 56" fill="none" stroke="#fff" stroke-linecap="round" stroke-linejoin="round">
            <path d="M32,75 C38,69 44,81 50,75 C56,69 62,81 68,75 C71,72 74,73 76,75" stroke-width="5" opacity="0.92" />
            <path d="M41,33 L57,49 L41,65" stroke-width="9" />
            <path d="M57,33 L73,49 L57,65" stroke-width="9" opacity="0.5" />
          </svg>
        </div>
        <div>
          <h3>Pherry</h3>
          <p>Photos, ferried. Local-network photo and video receiving for the desktop.</p>
        </div>
      </section>
    </div>
  `;

  const port = state.server.port || s.port || 3210;
  const settingsIps = document.querySelector("#settings-ips");
  settingsIps.innerHTML = (state.ips || []).map((ip) =>
    `<button class="ip-chip" type="button" data-ip="${escHtml(ip)}" aria-label="Copy http://${escHtml(ip)}:${port}">${escHtml(ip)}:${port} <span class="icon xs" aria-hidden="true">content_copy</span></button>`
  ).join("") || `<span style="color:var(--text-muted);font-size:12px;">No network interfaces detected.</span>`;
  settingsIps.querySelectorAll(".ip-chip").forEach((el) => {
    el.addEventListener("click", () => {
      navigator.clipboard?.writeText(`http://${el.dataset.ip}:${port}`);
      const bg = el.style.background;
      el.style.background = "var(--success-bg)";
      setTimeout(() => (el.style.background = bg), 600);
    });
  });

  document.querySelectorAll(".seg [data-theme]").forEach((b) => {
    b.addEventListener("click", async () => {
      await window.api.updateSettings({ theme: b.dataset.theme });
      await refreshSettings();
      applyTheme();
      rerender();
    });
  });

  document.querySelector("#choose-folder")?.addEventListener("click", async () => {
    await window.api.chooseFolder();
    await refreshSettings();
    rerender();
  });

  document.querySelector("#apply-port")?.addEventListener("click", async () => {
    const p = Number(document.querySelector("#port-input").value);
    if (!Number.isFinite(p) || p < 1024 || p > 65535) {
      showToast("Port must be between 1024 and 65535.", "error");
      return;
    }
    const res = await window.api.updateSettings({ port: p });
    await refreshSettings();
    applyTheme();
    renderFooter();
    if (!res?.success && res?.error) showToast("Failed to bind to that port: " + res.error, "error");
    rerender();
  });

  document.querySelector("#opt-auto-open")?.addEventListener("change", async (e) => {
    await window.api.updateSettings({ autoOpenFolder: e.target.checked });
    await refreshSettings();
  });

  document.querySelector("#opt-notify")?.addEventListener("change", async (e) => {
    await window.api.updateSettings({ notifyOnArrival: e.target.checked });
    await refreshSettings();
  });

  document.querySelector("#opt-tray")?.addEventListener("change", async (e) => {
    await window.api.updateSettings({ minimizeToTray: e.target.checked });
    await refreshSettings();
  });

  document.querySelector("#opt-launch")?.addEventListener("change", async (e) => {
    await window.api.updateSettings({ launchAtStartup: e.target.checked });
    await refreshSettings();
  });

  document.querySelector("#rotate-token")?.addEventListener("click", async () => {
    const res = await window.api.rotatePairingToken();
    await refreshSettings();
    if (res?.success) {
      showToast("New pairing code generated. Re-scan the QR on your phone to keep delete access.");
    } else {
      showToast("Couldn't rotate the pairing code: " + (res?.error || "unknown error"), "error");
    }
    rerender();
  });

  document.querySelector("#export-history-settings")?.addEventListener("click", exportHistory);
  document.querySelector("#import-history-settings")?.addEventListener("click", importHistory);
  document.querySelector("#rebuild-index-settings")?.addEventListener("click", rebuildHistoryIndex);
  document.querySelector("#clean-duplicates-settings")?.addEventListener("click", cleanDuplicates);

  updateRebuildButton();
  if (state.rebuild.running) startRebuildPolling();
}

function themeTab(value, label, current) {
  const active = current === value;
  return `<button role="tab" aria-selected="${active}" data-theme="${value}" class="${active ? "active" : ""}">${label}</button>`;
}

register("settings", renderSettings);
export { renderSettings };
