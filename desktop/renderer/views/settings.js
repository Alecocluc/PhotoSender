import { state, refreshSettings, refreshIPs, sessionActive, computerName } from '../state.js';
import { escHtml, sortedIPs } from '../utils.js';
import { register, rerender } from '../router.js';
import {
  exportHistory, importHistory,
  rebuildHistoryIndex, cleanDuplicates,
  updateRebuildButton, startRebuildPolling,
} from '../actions.js';
import { icon } from '../icons.js';
import { applyTheme, renderStation, showConfirm, showToast } from '../shell.js';

const MIN_PORT = 1024;
const MAX_PORT = 65535;

/** What the user typed into the port field but hasn't applied; survives re-renders from server events. */
let portDraft = null;
let portError = "";
let portBusy = false;

function currentPort() {
  return Number(state.server.port || state.settings?.port || 3210);
}

/** Turns a bind error into words: "another program is already using it". */
function portProblem(err) {
  const text = String(err || "");
  if (/EADDRINUSE/i.test(text)) return "another program is already using it";
  if (/EACCES/i.test(text)) return "this computer didn't allow it";
  return text || "the receiver couldn't start";
}

function trayWord() {
  return state.host.platform === "darwin" ? "menu bar" : "system tray";
}

// ── Pieces ───────────────────────────────────────────────────────────────

function row({ name, forId = "", help = "", helpId = "", control, cls = "" }) {
  const title = forId
    ? `<label class="set-name" for="${forId}">${name}</label>`
    : `<span class="set-name">${name}</span>`;
  return `
    <div class="set-row ${cls}">
      <div class="set-label">
        ${title}
        ${help ? `<p class="set-help"${helpId ? ` id="${helpId}"` : ""}>${help}</p>` : ""}
      </div>
      <div class="set-control">${control}</div>
    </div>`;
}

function toggleRow({ id, name, help, checked }) {
  return row({
    name, forId: id, help, helpId: `${id}-help`, cls: "is-toggle",
    control: `
      <span class="switch">
        <input type="checkbox" role="switch" id="${id}" aria-describedby="${id}-help" ${checked ? "checked" : ""} />
        <span class="track" aria-hidden="true"></span>
      </span>`,
  });
}

function noticeHtml(port) {
  if (!state.server.error) return "";
  return `
    <div class="notice set-notice" role="alert">${icon("warning-circle", { size: 20 })}
      <div class="notice-text">Pherry isn't receiving.<span>Port ${escHtml(String(port))} couldn't be opened: ${escHtml(portProblem(state.server.error))}. Pick another port below.</span></div>
      <button class="btn danger sm" id="focus-port">Change port</button>
    </div>`;
}

function receivingHtml(s, port) {
  const draft = portDraft ?? String(port);
  const ips = sortedIPs(state.ips);
  const addresses = ips.length
    ? `<ul class="set-addrs">${ips.map((ip, i) => {
        const addr = `${ip}:${port}`;
        return `
          <li>
            <span class="mono">${escHtml(addr)}</span>
            ${i === 0 ? `<span class="tag quiet">On the ticket</span>` : ""}
            <button class="btn sm quiet" data-copy="${escHtml(addr)}" aria-label="Copy ${escHtml(addr)}">${icon("copy", { size: 15 })}<span>Copy</span></button>
          </li>`;
      }).join("")}</ul>`
    : `<p class="set-empty">No network found. Connect this computer to Wi-Fi or Ethernet.</p>`;

  return `
    <section class="print set-block" aria-labelledby="set-receiving">
      <h2 class="section-title" id="set-receiving">Receiving</h2>
      ${row({
        name: "Pherry folder", forId: "dl-path", helpId: "dl-path-help",
        help: "Photos and videos are saved here, one folder per album. Files already saved stay where they are, and Pherry only recognises files in this folder: move them over and rebuild the duplicate index, or a backup check from a phone sends them again.",
        control: `
          <input class="input mono set-path" id="dl-path" value="${escHtml(s.downloadPath || "")}" readonly aria-describedby="dl-path-help" />
          <button class="btn" id="choose-folder">Choose…</button>
          <button class="btn quiet" id="open-dl-folder">${icon("folder-open", { size: 17 })}Open</button>`,
      })}
      ${row({
        name: "Port", forId: "port-input", helpId: "port-help",
        help: `A number from ${MIN_PORT} to ${MAX_PORT}. Applying restarts the receiver, and phones need to pair again with the new address.`,
        control: `
          <input class="input mono set-port" id="port-input" type="number" inputmode="numeric" min="${MIN_PORT}" max="${MAX_PORT}" step="1"
            value="${escHtml(draft)}" aria-describedby="port-help${portError ? " port-error" : ""}" ${portError ? `aria-invalid="true"` : ""} />
          <button class="btn primary" id="apply-port" ${portBusy || Number(draft) === port ? "disabled" : ""}>${portBusy ? "Applying…" : "Apply"}</button>
          ${portError ? `<p class="set-error" id="port-error">${icon("warning-circle", { size: 16 })}<span>${escHtml(portError)}</span></p>` : ""}`,
      })}
      ${row({
        name: "Addresses on this computer",
        help: "Type one of these on your phone if scanning the ticket doesn't work. Phone and computer need to be on the same Wi-Fi.",
        control: addresses,
      })}
    </section>`;
}

function pairingHtml(s) {
  const token = s.pairingToken || "";
  return `
    <section class="print set-block" aria-labelledby="set-pairing">
      <h2 class="section-title" id="set-pairing">Pairing code</h2>
      <div class="set-pair">
        <div class="set-stub">
          <span class="perf" aria-hidden="true"></span>
          <div class="set-stub-in">
            <span class="label">Pairing code</span>
            <span class="visually-hidden">${token ? escHtml(token.split("").join(" ")) : "Not set yet"}</span>
            <span class="set-code" aria-hidden="true">${escHtml(token || "------")}</span>
          </div>
        </div>
        <div class="set-pair-text">
          <p>Phones that scanned this code can delete files here when they use Sync. Get a new code if you shared it with someone you don't trust; paired phones then need to scan again.</p>
          <button class="btn danger" id="rotate-token">${icon("arrows-clockwise", { size: 17 })}Get a new code</button>
        </div>
      </div>
    </section>`;
}

function behaviourHtml(s) {
  return `
    <section class="print set-block" aria-labelledby="set-behaviour">
      <h2 class="section-title" id="set-behaviour">Behaviour</h2>
      ${toggleRow({
        id: "opt-notify", name: "Show a notification when files arrive", checked: s.notifyOnArrival !== false,
        help: "Only while the Pherry window isn't in front.",
      })}
      ${toggleRow({
        id: "opt-auto-open", name: "Open the folder after each file", checked: !!s.autoOpenFolder,
        help: "Opens the album's folder on this computer every time a file lands.",
      })}
      ${toggleRow({
        id: "opt-tray", name: "Keep receiving when the window is closed", checked: s.minimizeToTray !== false,
        help: `Closing the window leaves Pherry in the ${trayWord()}, still receiving. Quit from its icon there.`,
      })}
      ${toggleRow({
        id: "opt-launch", name: "Start Pherry when you sign in", checked: !!s.launchAtStartup,
        help: "Pherry starts receiving as soon as you sign in to this computer.",
      })}
    </section>`;
}

function maintenanceHtml() {
  return `
    <section class="print set-block" aria-labelledby="set-maintenance">
      <h2 class="section-title" id="set-maintenance">Maintenance</h2>
      ${row({
        name: "Back up the history",
        help: "Save the record of files this computer has received to a file, or restore it from one. Photos and videos aren't included.",
        control: `
          <button class="btn" id="export-history">${icon("download-simple", { size: 17 })}Export</button>
          <button class="btn" id="import-history">${icon("upload-simple", { size: 17 })}Import</button>`,
      })}
      ${row({
        name: "Rebuild the duplicate index",
        help: "Pherry checks every file in the Pherry folder so it can recognise copies. Do this after moving or adding files by hand.",
        control: `
          <button class="btn" id="rebuild-index">${icon("arrows-clockwise", { size: 17 })}<span>Rebuild</span></button>
          <p class="set-status mono" id="rebuild-status" tabindex="-1" hidden></p>`,
      })}
      ${row({
        name: "Delete duplicate copies",
        help: "Finds files saved more than once and deletes the extra copies, keeping the oldest. You see how many before anything is deleted.",
        control: `<button class="btn danger" id="clean-duplicates">${icon("broom", { size: 17 })}Delete duplicates…</button>`,
      })}
    </section>`;
}

function appearanceHtml(s) {
  const current = s.theme || "system";
  const seg = (value, label) =>
    `<button type="button" id="theme-${value}" data-theme-choice="${value}" aria-pressed="${current === value}">${label}</button>`;
  return `
    <section class="print set-block" aria-labelledby="set-appearance">
      <h2 class="section-title" id="set-appearance">Appearance</h2>
      ${row({
        name: "Theme",
        help: "System follows your computer's light or dark setting.",
        control: `<div class="seg" role="group" aria-label="Theme">${seg("system", "System")}${seg("light", "Light")}${seg("dark", "Dark")}</div>`,
      })}
    </section>`;
}

function aboutHtml() {
  const version = state.host.version;
  return `
    <section class="print set-block" aria-labelledby="set-about">
      <h2 class="section-title" id="set-about">About</h2>
      <div class="set-about">
        <img src="pherry-icon.svg" width="40" height="40" alt="" />
        <div>
          <p class="set-about-name">Pherry${version ? ` <span class="mono">${escHtml(version)}</span>` : ""}</p>
          <p class="set-help">Open source, MIT licence. Photos travel only between your phone and this computer.</p>
        </div>
      </div>
    </section>`;
}

// ── Render ───────────────────────────────────────────────────────────────

function render() {
  const root = document.querySelector("#view-root");
  const active = document.activeElement;
  const focusId = active?.id && root.contains(active) ? active.id : null;

  const s = state.settings || {};
  const port = currentPort();
  const running = state.server.running && !state.server.error;
  root.innerHTML = `
    <header class="view-head">
      <div>
        <h1 class="view-title" id="settings-title">Settings</h1>
        <div class="view-sub">${escHtml(computerName())} · ${running ? `Receiving on port ${escHtml(String(port))}` : "Receiver stopped"}</div>
      </div>
    </header>
    ${noticeHtml(port)}
    <div class="settings">
      ${receivingHtml(s, port)}
      ${pairingHtml(s)}
      ${behaviourHtml(s)}
      ${maintenanceHtml()}
      ${appearanceHtml(s)}
      ${aboutHtml()}
    </div>`;

  wire();
  if (focusId) document.getElementById(focusId)?.focus({ preventScroll: true });
}

// ── Actions ──────────────────────────────────────────────────────────────

/** Port and folder changes restart the receiver; say so first if a phone is mid-transfer. */
async function okToRestart(change) {
  if (!sessionActive()) return true;
  return showConfirm({
    title: "Restart the receiver now?",
    message: `<strong>${escHtml(state.session?.device || "A phone")}</strong> is sending right now. ${escHtml(change)} restarts the receiver, which can interrupt that transfer.`,
    confirmText: "Restart now",
  });
}

function showPortError(message) {
  portError = message;
  const input = document.querySelector("#port-input");
  const control = input?.closest(".set-control");
  if (!input || !control) return;
  control.querySelector("#port-error")?.remove();
  if (message) {
    control.insertAdjacentHTML("beforeend",
      `<p class="set-error" id="port-error">${icon("warning-circle", { size: 16 })}<span>${escHtml(message)}</span></p>`);
    input.setAttribute("aria-invalid", "true");
    input.setAttribute("aria-describedby", "port-help port-error");
  } else {
    input.removeAttribute("aria-invalid");
    input.setAttribute("aria-describedby", "port-help");
  }
}

async function applyPort() {
  if (portBusy) return;
  const input = document.querySelector("#port-input");
  const raw = String(input?.value ?? "").trim();
  const p = Number(raw);
  if (!/^\d+$/.test(raw) || p < MIN_PORT || p > MAX_PORT) {
    showPortError(`Use a whole number from ${MIN_PORT} to ${MAX_PORT}.`);
    input?.focus();
    return;
  }
  const prev = currentPort();
  if (p === prev) {
    portDraft = null;
    showPortError("");
    return;
  }
  if (!(await okToRestart("Changing the port"))) return;

  portBusy = true;
  portError = "";
  rerender();
  let res;
  try {
    res = await window.api.updateSettings({ port: p });
  } catch (err) {
    res = { success: false, error: err?.message };
  }
  await refreshSettings();
  portBusy = false;
  if (res?.success) {
    portDraft = null;
    state.server.port = state.settings?.port || p;
    const ip = sortedIPs(state.ips)[0];
    showToast(ip
      ? `Receiving on port ${p}. Pair your phone again with ${ip}:${p}.`
      : `Receiving on port ${p}. Pair your phone again with the new address.`);
  } else {
    portError = `Port ${p} couldn't be opened: ${portProblem(res?.error)}. Pherry kept port ${prev}; try another number.`;
  }
  renderStation();
  rerender();
  if (portError) document.querySelector("#port-input")?.focus();
}

async function chooseFolder() {
  const before = state.settings?.downloadPath || "";
  if (before) {
    // The picker applies the folder as soon as one is chosen, so say what changing it costs first.
    const ok = await showConfirm({
      title: "Use a different folder?",
      message: `Files already in <strong class="mono">${escHtml(before)}</strong> stay there. Pherry won't recognise them in the new folder, so a phone's backup check will send them again. To avoid that, move them into the new folder first, then use Rebuild the duplicate index.`
        + (sessionActive() ? ` <strong>${escHtml(state.session?.device || "A phone")}</strong> is sending right now, and changing the folder restarts the receiver, which can interrupt that transfer.` : ""),
      confirmText: "Change folder",
    });
    if (!ok) return;
  } else if (!(await okToRestart("Choosing another folder"))) return;
  let chosen;
  try {
    chosen = await window.api.chooseFolder();
  } catch {
    showToast("Couldn't open the folder picker. Try again.", "error");
    return;
  }
  await refreshSettings();
  renderStation();
  rerender();
  if (chosen && chosen !== before) showToast(`New files are saved to ${chosen}.`);
}

async function rotateToken() {
  const old = state.settings?.pairingToken || "";
  const ok = await showConfirm({
    title: "Get a new pairing code?",
    message: `The code <strong class="mono set-code-inline">${escHtml(old)}</strong> stops working. Phones can still send photos, but they can't delete files here with Sync until they scan the new ticket.`
      + (sessionActive() ? " A phone is sending right now, and restarting the receiver can interrupt it." : ""),
    confirmText: "Get a new code",
    danger: true,
  });
  if (!ok) return;
  let res;
  try {
    res = await window.api.rotatePairingToken();
  } catch (err) {
    res = { success: false, error: err?.message };
  }
  await refreshSettings();
  renderStation();
  rerender();
  if (res?.success) {
    showToast("New pairing code ready. Scan the ticket again on your phones to keep using Sync.");
  } else {
    showToast(`Couldn't get a new code${res?.error ? `: ${res.error}` : ""}. Try again.`, "error");
  }
}

async function saveToggle(input, key) {
  const value = input.checked;
  try {
    const res = await window.api.updateSettings({ [key]: value });
    if (res && res.success === false) throw new Error(res.error);
    await refreshSettings();
  } catch {
    input.checked = !value;
    showToast("Couldn't save that setting. Try again.", "error");
  }
}

async function copyAddress(text, btn) {
  try {
    await navigator.clipboard.writeText(text);
    showToast(`Copied ${text}`);
    const label = btn?.querySelector("span");
    if (label) {
      label.textContent = "Copied";
      setTimeout(() => { label.textContent = "Copy"; }, 1400);
    }
  } catch {
    showToast("Couldn't copy. Select the address and copy it by hand.", "error");
  }
}

function wire() {
  const $ = (sel) => document.querySelector(sel);

  $("#focus-port")?.addEventListener("click", () => {
    const input = $("#port-input");
    input?.scrollIntoView({ block: "center" });
    input?.focus({ preventScroll: true });
    input?.select();
  });

  $("#choose-folder")?.addEventListener("click", chooseFolder);
  $("#open-dl-folder")?.addEventListener("click", () => window.api.openFolder());

  const portInput = $("#port-input");
  portInput?.addEventListener("input", () => {
    portDraft = portInput.value;
    if (portError) showPortError("");
    const apply = $("#apply-port");
    if (apply && !portBusy) apply.disabled = Number(portInput.value) === currentPort() || portInput.value.trim() === "";
  });
  portInput?.addEventListener("keydown", (e) => {
    if (e.key === "Enter") { e.preventDefault(); applyPort(); }
    if (e.key === "Escape" && portDraft !== null) { e.preventDefault(); portDraft = null; showPortError(""); rerender(); }
  });
  $("#apply-port")?.addEventListener("click", applyPort);

  document.querySelectorAll(".set-addrs [data-copy]").forEach((b) =>
    b.addEventListener("click", () => copyAddress(b.dataset.copy, b)));

  $("#rotate-token")?.addEventListener("click", rotateToken);

  $("#opt-notify")?.addEventListener("change", (e) => saveToggle(e.target, "notifyOnArrival"));
  $("#opt-auto-open")?.addEventListener("change", (e) => saveToggle(e.target, "autoOpenFolder"));
  $("#opt-tray")?.addEventListener("change", (e) => saveToggle(e.target, "minimizeToTray"));
  $("#opt-launch")?.addEventListener("change", (e) => saveToggle(e.target, "launchAtStartup"));

  $("#export-history")?.addEventListener("click", exportHistory);
  $("#import-history")?.addEventListener("click", importHistory);
  $("#rebuild-index")?.addEventListener("click", rebuildHistoryIndex);
  $("#clean-duplicates")?.addEventListener("click", cleanDuplicates);

  document.querySelectorAll("[data-theme-choice]").forEach((b) => {
    b.addEventListener("click", async () => {
      if (b.getAttribute("aria-pressed") === "true") return;
      try {
        await window.api.updateSettings({ theme: b.dataset.themeChoice });
      } catch {
        showToast("Couldn't change the theme. Try again.", "error");
        return;
      }
      await refreshSettings();
      applyTheme();
      rerender();
    });
  });

  updateRebuildButton();
  if (state.rebuild.running) startRebuildPolling();
}

// Entering the view drops an unapplied port draft and re-reads the network addresses, which change with Wi-Fi.
register("settings", () => {
  const entering = !document.querySelector("#settings-title");
  if (entering && !portBusy) {
    portDraft = null;
    portError = "";
  }
  render();
  if (!entering) return;
  const before = sortedIPs(state.ips).join(",");
  refreshIPs().then(() => {
    if (state.view === "settings" && sortedIPs(state.ips).join(",") !== before) {
      renderStation();
      render();
    }
  });
});
export { render as renderSettings };
