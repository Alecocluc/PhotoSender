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
    <div class="settings-grid">
      <div class="card">
        <div class="label-row"><span>Appearance</span></div>
        <div class="form-row">
          <label>Theme</label>
          <div class="seg" role="tablist" aria-label="Theme">
            ${themeTab("system", "System", s.theme || "system")}
            ${themeTab("light", "Light", s.theme || "system")}
            ${themeTab("dark", "Dark", s.theme || "system")}
          </div>
        </div>
      </div>

      <div class="card">
        <div class="label-row"><span>Server</span></div>
        <div class="form-row">
          <label>Download folder</label>
          <div class="row-flex">
            <input class="input" id="dl-path" value="${escHtml(s.downloadPath || "")}" readonly />
            <button class="btn" id="choose-folder"><span class="icon sm">folder_open</span>Browse…</button>
          </div>
        </div>
        <div class="form-row">
          <label>Port <span class="help">(1024–65535, restarts server on change)</span></label>
          <div class="row-flex">
            <input class="input" id="port-input" type="number" min="1024" max="65535" value="${escHtml(s.port || 3210)}" style="max-width:180px;" />
            <button class="btn primary" id="apply-port">Apply</button>
          </div>
        </div>
        <div class="form-row" style="margin-bottom:0;">
          <label>Available addresses</label>
          <div class="ips" id="settings-ips"></div>
        </div>
      </div>

      <div class="card">
        <div class="label-row"><span>Behavior</span></div>
        <label class="switch" style="display:flex;justify-content:space-between;padding:8px 0;">
          <span style="font-weight:600;">Auto-open folder on transfer</span>
          <input type="checkbox" id="opt-auto-open" ${s.autoOpenFolder ? "checked" : ""} /><span class="track"></span>
        </label>
        <label class="switch" style="display:flex;justify-content:space-between;padding:8px 0;">
          <span style="font-weight:600;">Launch at startup</span>
          <input type="checkbox" id="opt-launch" ${s.launchAtStartup ? "checked" : ""} /><span class="track"></span>
        </label>
      </div>

      <div class="card">
        <div class="label-row"><span>History Backup</span></div>
        <div class="form-row">
          <label>Desktop dedup index <span class="help">Use this when moving the backup to another PC.</span></label>
          <div class="row-flex">
            <button class="btn" id="export-history-settings"><span class="icon sm">download</span>Export</button>
            <button class="btn" id="import-history-settings"><span class="icon sm">upload_file</span>Import</button>
            <button class="btn" id="rebuild-index-settings"><span class="icon sm">sync</span>Rebuild</button>
          </div>
        </div>
        <div class="form-row">
          <label>Duplicate files <span class="help">Deletes extra copies of identical files, keeping one each. Run Rebuild first for an accurate scan.</span></label>
          <div class="row-flex">
            <button class="btn" id="clean-duplicates-settings"><span class="icon sm">delete_sweep</span>Clean duplicates</button>
          </div>
        </div>
      </div>
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

  document.querySelector("#opt-launch")?.addEventListener("change", async (e) => {
    await window.api.updateSettings({ launchAtStartup: e.target.checked });
    await refreshSettings();
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
