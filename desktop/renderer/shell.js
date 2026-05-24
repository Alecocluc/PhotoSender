import { state } from './state.js';
import { primaryIP, escHtml } from './utils.js';

const mq = window.matchMedia("(prefers-color-scheme: dark)");

export function applyTheme() {
  const pref = state.settings?.theme || "system";
  const resolved = pref === "dark" || pref === "light" ? pref : (mq.matches ? "dark" : "light");
  document.documentElement.setAttribute("data-theme", resolved);
  const icon = document.querySelector("#theme-icon");
  if (icon) icon.textContent = resolved === "dark" ? "light_mode" : "dark_mode";
}

mq.addEventListener("change", () => {
  if ((state.settings?.theme || "system") === "system") applyTheme();
});

export function renderFooter() {
  const port = state.server.port || state.settings?.port || 3210;
  document.querySelector("#server-port").textContent = port;
  document.querySelector("#server-ip").textContent = primaryIP(state.ips);
  const dot = document.querySelector("#server-dot");
  const statusText = document.querySelector("#server-status-text");
  const linkPill = document.querySelector("#link-pill");
  const chip = document.querySelector("#server-chip");
  if (state.server.running) {
    dot.classList.remove("off");
    statusText.textContent = "Active";
    linkPill.textContent = "All good";
    linkPill.style.background = "var(--success-bg)";
    linkPill.style.color = "var(--success-fg)";
    chip.style.background = "var(--success-bg)";
    chip.style.color = "var(--success-fg)";
    chip.innerHTML = '<span class="dot"></span>Active';
  } else {
    dot.classList.add("off");
    statusText.textContent = "Offline";
    linkPill.textContent = state.server.error ? "Connection error" : "Server offline";
    linkPill.style.background = "var(--danger-bg)";
    linkPill.style.color = "var(--danger)";
    chip.style.background = "var(--danger-bg)";
    chip.style.color = "var(--danger)";
    chip.innerHTML = '<span class="dot"></span>Offline';
  }
}

export function renderDevices() {
  const items = state.history.items || [];
  const seen = new Set();
  for (const it of items.slice(0, 30)) {
    if (it.deviceName) seen.add(it.deviceName);
    else if (it.bucketName) seen.add(it.bucketName);
  }
  const list = document.querySelector("#device-list");
  if (!list) return;
  if (seen.size === 0) {
    list.innerHTML = `<div class="device" title="No sources yet"><span class="icon xs">phonelink_off</span></div>`;
    return;
  }
  const ICONS = ["smartphone", "tablet_android", "laptop_mac", "computer", "watch"];
  list.innerHTML = [...seen].slice(0, 6).map((name, i) =>
    `<div class="device online" title="${escHtml(name)}"><span class="icon xs">${ICONS[i % ICONS.length]}</span></div>`
  ).join("");
}
