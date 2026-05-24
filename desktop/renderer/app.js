import { state, refreshStatus, refreshHistory, refreshIPs, refreshSettings } from './state.js';
import { fmtUptime } from './utils.js';
import { applyTheme, renderFooter, renderDevices } from './shell.js';
import { navigate, initSidebarHelper, rerender } from './router.js';
import './views/dashboard.js';
import './views/activity.js';
import './views/history.js';
import './views/settings.js';

function openSidebar() {
  document.querySelector("#sidebar").classList.add("open");
  document.querySelector("#scrim").classList.add("open");
}

function closeSidebar() {
  document.querySelector("#sidebar").classList.remove("open");
  document.querySelector("#scrim").classList.remove("open");
}

initSidebarHelper(closeSidebar);

document.querySelector("#hamburger").addEventListener("click", openSidebar);
document.querySelector("#scrim").addEventListener("click", closeSidebar);
document.querySelectorAll(".nav-item").forEach((el) => {
  el.addEventListener("click", () => navigate(el.dataset.view));
});

document.querySelector("#theme-toggle").addEventListener("click", async () => {
  const cur = state.settings?.theme || "system";
  const order = { system: "light", light: "dark", dark: "system" };
  await window.api.updateSettings({ theme: order[cur] });
  await refreshSettings();
  applyTheme();
  if (state.view === "settings") rerender();
});

document.querySelector("#open-folder-btn").addEventListener("click", () => window.api.openFolder());

window.api.onFileReceived((entry) => {
  state.history.items = [entry, ...(state.history.items || [])].slice(0, 200);
  state.history.totalCount = (state.history.totalCount || 0) + 1;
  state.history.nextOffset = Math.min((state.history.nextOffset || 0) + 1, state.history.totalCount);
  state.history.hasMore = state.history.items.length < state.history.totalCount;
  if (state.status) {
    state.status.totalReceived = (state.status.totalReceived || 0) + 1;
    state.status.totalBytes = (state.status.totalBytes || 0) + (entry.size || 0);
    state.status.recentActivity = [entry, ...(state.status.recentActivity || [])].slice(0, 50);
  }
  renderDevices();
  if (["dashboard", "activity", "history"].includes(state.view)) rerender();
});

window.api.onServerState((s) => {
  state.server = { ...state.server, ...s };
  renderFooter();
  if (["dashboard", "settings"].includes(state.view)) rerender();
});

setInterval(() => {
  if (state.view === "dashboard" && state.status) {
    const el = document.getElementById("uptime-display");
    if (el) {
      state.status.uptimeMs = (state.status.uptimeMs || 0) + 1000;
      el.textContent = fmtUptime(state.status.uptimeMs);
    }
  }
}, 1000);

setInterval(async () => {
  await refreshStatus();
  if (state.view === "dashboard") rerender();
}, 10000);

(async () => {
  await refreshSettings();
  applyTheme();
  state.server.port = state.settings?.port || 3210;
  navigate("dashboard");
  await Promise.all([refreshStatus(), refreshHistory(), refreshIPs()]);
  state.initializing = false;
  renderDevices();
  renderFooter();
  rerender();
})();
