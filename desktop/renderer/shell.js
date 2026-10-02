import { state, jobState, computerName } from './state.js';
import { primaryIP, escHtml } from './utils.js';
import { icon } from './icons.js';

const mq = window.matchMedia("(prefers-color-scheme: dark)");

export function applyTheme() {
  const pref = state.settings?.theme || "system";
  const resolved = pref === "dark" || pref === "light" ? pref : (mq.matches ? "dark" : "light");
  document.documentElement.setAttribute("data-theme", resolved);
  const btn = document.querySelector("#theme-toggle");
  if (btn) {
    const next = { system: "light", light: "dark", dark: "system" }[pref];
    const label = `Theme: ${pref}. Switch to ${next}`;
    btn.innerHTML = icon(resolved === "dark" ? "moon" : "sun", { size: 18 });
    btn.title = label;
    btn.setAttribute("aria-label", label);
  }
}

mq.addEventListener("change", () => {
  if ((state.settings?.theme || "system") === "system") applyTheme();
});

/** Static rail icons, rendered once at boot. */
export function renderNav() {
  document.querySelectorAll(".nav-item[data-icon]").forEach((el) => {
    const name = el.dataset.icon;
    el.insertAdjacentHTML("afterbegin",
      icon(name, { size: 20, cls: "ic-reg" }) + icon(name, { weight: "fill", size: 20, cls: "ic-fill" }));
  });
  const folderBtn = document.querySelector("#open-folder-btn");
  if (folderBtn) folderBtn.innerHTML = icon("folder-open", { size: 18 });
  // index.html marks the whole station live, which would re-announce the address and buttons on
  // every refresh. Only the status word speaks, and only when it changes (see renderStation).
  document.querySelector("#station")?.removeAttribute("aria-live");
  document.querySelector("#station-text")?.setAttribute("aria-live", "polite");
}

/** DOM writes only when the value differs, so a periodic refresh never re-triggers the live region. */
function setText(el, value) {
  if (el.textContent !== value) el.textContent = value;
}
function setProp(el, prop, value) {
  if (el[prop] !== value) el[prop] = value;
}
function setAttr(el, name, value) {
  if (el.getAttribute(name) !== value) el.setAttribute(name, value);
}

/** Rail foot: is this computer receiving, and where. */
export function renderStation() {
  const lamp = document.querySelector("#station-lamp");
  const text = document.querySelector("#station-text");
  const addr = document.querySelector("#station-addr");
  if (!lamp || !text || !addr) return;
  const port = state.server.port || state.settings?.port || 3210;
  const ip = primaryIP(state.ips);
  let lampClass, words, addrText, addrTitle, lampTitle;
  if (state.server.running === null) {
    // Not known yet: the main process hasn't said whether the port opened.
    lampClass = "lamp";
    words = "Starting…";
    addrText = "";
    addrTitle = "";
    lampTitle = "Starting…";
  } else if (state.server.running) {
    const receiving = state.status?.receiving?.length > 0 || state.jobs.some((job) => ['running', 'receiving'].includes(jobState(job)));
    lampClass = "lamp on";
    words = receiving ? "Receiving" : "Ready";
    addrText = ip !== "-" ? `${ip}:${port}` : "No network";
    addrTitle = `${computerName()} · ${ip}:${port}`;
    lampTitle = ip !== "-" ? `${receiving ? 'Receiving' : 'Ready to receive'} on ${ip}:${port}` : "This computer isn't on a network";
  } else {
    lampClass = "lamp off";
    words = "Stopped";
    addrText = state.server.error ? "Port unavailable" : `Port ${port}`;
    addrTitle = state.server.error || "";
    lampTitle = state.server.error ? `Stopped: ${state.server.error}` : "Stopped";
  }
  setProp(lamp, "className", lampClass);
  setText(text, words);
  setText(addr, addrText);
  setProp(addr, "title", addrTitle);
  setProp(lamp, "title", lampTitle);
  // The narrow rail hides the words, so the lamp carries them as a tooltip and a spoken label.
  setAttr(lamp, "role", "img");
  setAttr(lamp, "aria-label", lampTitle);
  const count = document.querySelector("#nav-photos-count");
  if (count) setText(count, state.status?.mediaCount ? Number(state.status.mediaCount).toLocaleString() : "");
}

/**
 * The live region a toast goes into: errors are alerts, everything else is a polite status. The
 * regions ship in index.html so they are registered before the first message; if one is missing
 * it is created here, and `created` tells the caller to wait a frame before filling it.
 */
function toastRegion(variant) {
  const error = variant === "error";
  const id = error ? "toast-alert" : "toast-status";
  let region = document.getElementById(id);
  if (region) return { region, created: false };
  let root = document.querySelector("#toast-root");
  if (!root) {
    root = document.createElement("div");
    root.id = "toast-root";
    root.className = "toast-root";
    document.body.appendChild(root);
  }
  region = document.createElement("div");
  region.id = id;
  region.className = "toast-stack";
  region.setAttribute("role", error ? "alert" : "status");
  region.setAttribute("aria-live", error ? "assertive" : "polite");
  if (error) root.appendChild(region); else root.prepend(region);
  return { region, created: true };
}

export function showToast(message, variant = "info") {
  const { region, created } = toastRegion(variant);
  const item = document.createElement("div");
  item.className = `toast ${variant}`;
  item.innerHTML = `${icon(variant === "error" ? "warning-circle" : "check", { size: 18, weight: variant === "error" ? "regular" : "bold" })}<span>${escHtml(message)}</span>`;
  const show = () => {
    region.appendChild(item);
    requestAnimationFrame(() => item.classList.add("show"));
  };
  if (created) requestAnimationFrame(show); else show();
  setTimeout(() => {
    item.classList.remove("show");
    setTimeout(() => item.remove(), 260);
  }, variant === "error" ? 6000 : 3400);
}

/** Confirmation dialog. Resolves true on confirm. Escape and the scrim cancel. */
export function showConfirm({ title, message, confirmText = "Confirm", cancelText = "Cancel", danger = false }) {
  return new Promise((resolve) => {
    const previous = document.activeElement;
    const overlay = document.createElement("div");
    overlay.className = "modal-overlay";
    overlay.innerHTML = `
      <div class="modal ${danger ? "danger" : ""}" role="alertdialog" aria-modal="true" aria-labelledby="modal-title" aria-describedby="modal-msg">
        <div class="modal-body">
          <h2 id="modal-title">${escHtml(title)}</h2>
          <p id="modal-msg">${message}</p>
        </div>
        <div class="modal-actions">
          <button class="btn quiet" data-action="cancel">${escHtml(cancelText)}</button>
          <button class="btn primary ${danger ? "danger" : ""}" data-action="confirm">${escHtml(confirmText)}</button>
        </div>
      </div>`;
    const close = (value) => {
      overlay.classList.remove("show");
      document.removeEventListener("keydown", onKey, true);
      setTimeout(() => overlay.remove(), 180);
      previous?.focus?.();
      resolve(value);
    };
    const onKey = (e) => {
      if (e.key === "Escape") { e.preventDefault(); close(false); }
      if (e.key === "Tab") {
        const btns = [...overlay.querySelectorAll("button")];
        const i = btns.indexOf(document.activeElement);
        e.preventDefault();
        btns[(i + (e.shiftKey ? -1 : 1) + btns.length) % btns.length].focus();
      }
    };
    overlay.addEventListener("click", (e) => { if (e.target === overlay) close(false); });
    overlay.querySelector("[data-action='cancel']").addEventListener("click", () => close(false));
    overlay.querySelector("[data-action='confirm']").addEventListener("click", () => close(true));
    document.addEventListener("keydown", onKey, true);
    document.body.appendChild(overlay);
    requestAnimationFrame(() => {
      overlay.classList.add("show");
      // Destructive dialogs start on Cancel so Enter never deletes by accident.
      overlay.querySelector(danger ? "[data-action='cancel']" : "[data-action='confirm']").focus();
    });
  });
}

// Transitional alias while the remaining views move to the new shell.
export const renderFooter = renderStation;
