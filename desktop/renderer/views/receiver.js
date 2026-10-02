import { state, sessionActive, computerName } from '../state.js';
import {
  escHtml, entryName, entryTime, entryKey, fmtBytes, fmtSpeed, fmtClock, fmtUptime,
  primaryIP, sortedIPs, qrPayloadFor, sameDay, n, plural,
} from '../utils.js';
import { register, navigate } from '../router.js';
import { renderQrCode } from '../qr.js';
import { attachThumbs } from '../thumbs.js';
import { icon } from '../icons.js';
import { frameHtml, wireFrames, reuseFrames, emptyHtml } from '../components.js';
import { showToast } from '../shell.js';

const SHEET_MAX = 24;

function model() {
  const loading = state.initializing;
  const s = state.status || {};
  const port = state.server.port || state.settings?.port || 3210;
  const ip = loading ? "-" : primaryIP(state.ips);
  const address = ip !== "-" ? `${ip}:${port}` : "";
  const token = state.settings?.pairingToken || "";
  const items = (state.history.items?.length ? state.history.items : s.recentActivity) || [];
  const today = items.filter((e) => sameDay(entryTime(e), Date.now()));
  const shown = (today.length ? today : items).slice(0, SHEET_MAX);
  const todayBytes = today.reduce((sum, e) => sum + (e.size || 0), 0);
  const todayCapped = today.length === items.length && !!state.history.hasMore;
  // Files still streaming in (big videos take minutes before they count as arrivals).
  const receiving = Array.isArray(s.receiving) ? s.receiving : [];
  return {
    loading, s, port, ip, address, token, items, today, shown, todayBytes, todayCapped, receiving,
    live: (sessionActive() || receiving.length > 0) && state.server.running === true,
    stopped: state.server.running === false,
  };
}

/** The phone sending now: from the last arrival, or from an upload still streaming in. */
function sender(m) {
  return state.session?.device || m.receiving.find((r) => r.device)?.device || "your phone";
}

function headline(m) {
  // Say what the rail's station says until the receiver has reported in.
  if (m.loading) return "Starting…";
  if (m.stopped) return "Receiver stopped";
  if (m.live) return `Receiving from ${sender(m)}`;
  return "Ready to receive";
}

/** The file streaming in now, or "3 files in progress" when several are. */
function inProgress(m) {
  const r = m.receiving;
  if (!r.length) return "";
  if (r.length > 1) return `${n(r.length)} files in progress`;
  return r[0].fileName || "A file";
}

/** Bytes in and expected across the uploads streaming now; null when no size was announced. */
function streamProgress(m) {
  const r = m.receiving.filter((x) => x.total > 0);
  if (!r.length) return null;
  const bytes = r.reduce((sum, x) => sum + Math.min(x.bytes || 0, x.total), 0);
  const total = r.reduce((sum, x) => sum + x.total, 0);
  return { bytes, total, pct: Math.max(0, Math.min(100, Math.round((bytes / total) * 100))) };
}

function ticketHtml(m) {
  const others = sortedIPs(state.ips).slice(1);
  const body = m.loading
    ? `<div class="qr-frame is-empty" aria-hidden="true"></div>`
    : m.address
      ? `<div class="qr-frame"><canvas id="pair-qr" role="img" aria-label="Pairing QR code for ${escHtml(m.address)}"></canvas></div>`
      : `<div class="qr-frame is-empty">${icon("wifi-slash", { size: 32 })}<span>No network</span></div>`;
  return `
    <section class="ticket${m.live ? " is-quiet" : ""}" aria-labelledby="ticket-title">
      <div class="ticket-body">
        <h2 class="ticket-title" id="ticket-title">Pair a phone</h2>
        <p>${m.loading || m.address
          ? `In Pherry on your phone, tap <strong>Pair a computer</strong> and scan this ticket.`
          : `Connect this computer to Wi-Fi or Ethernet. Your phone needs to be on the same network.`}</p>
        ${body}
      </div>
      <div class="ticket-tear" aria-hidden="true"><span class="perf"></span></div>
      <div class="ticket-stub">
        <div class="stub-field">
          <span class="label">Pairing code</span>
          <span class="code" aria-label="Pairing code ${escHtml(m.token.split("").join(" "))}">${escHtml(m.token || "------")}</span>
        </div>
        <div class="stub-field">
          <span class="label">Address</span>
          <div class="addr-row">
            <span class="mono addr">${escHtml(m.loading ? "…" : m.address || "Not on a network")}</span>
            <button class="btn sm" id="copy-address" ${m.address ? "" : "disabled"}>${icon("copy", { size: 15 })}<span>Copy</span></button>
          </div>
          ${others.length ? `<details class="other-addrs"><summary>Other addresses on this computer</summary>
            <ul>${others.map((o) => `<li><button class="link mono" data-copy="${escHtml(`${o}:${m.port}`)}">${escHtml(`${o}:${m.port}`)}</button></li>`).join("")}</ul>
            <p>Use one of these if the phone can't reach the address above (VPNs and virtual adapters add extras).</p></details>` : ""}
        </div>
        <p class="stub-note">The code lets this phone remove files here when you use Sync. Shared it by mistake? Get a new one in <button class="link" id="go-settings">Settings</button>.</p>
      </div>
    </section>`;
}

/** The changing words of the job envelope, shared by its first render and its in-place updates. */
function jobFacts(m) {
  const s = state.session;
  const done = s?.count || 0;
  const streaming = m.receiving.length;
  const streamed = m.receiving.reduce((sum, r) => sum + (r.bytes || 0), 0);
  const speed = fmtSpeed(m.s.currentSpeedBytesPerSec);
  const now = inProgress(m);
  // Before the first file lands, count what's on its way rather than printing "0 files".
  const count = done || streaming;
  return {
    device: sender(m),
    count: n(count),
    unit: done ? (done === 1 ? "file" : "files") : (streaming === 1 ? "file on its way" : "files on their way"),
    bytes: fmtBytes((s?.bytes || 0) + streamed),
    speed: m.s.currentSpeedBytesPerSec > 0 ? `${speed.v} ${speed.u}` : "-",
    lastLabel: now ? "Now" : "Last file",
    last: now || s?.lastName || "-",
    progress: streamProgress(m),
  };
}

/** Hairline marks every 10%, like the phone's JobBar. */
const TICKS = Array.from({ length: 9 }, (_, i) => `<i class="tick" style="left:${(i + 1) * 10}%"></i>`).join("");

function progressText(p) {
  return p ? `${fmtBytes(p.bytes)} of ${fmtBytes(p.total)} · ${p.pct}%` : "";
}

// Not a live region: speed and file names change every tick. #job-status announces start and end.
// The count leads; the phone is a printed field beside it, its busy lamp marking the live link.
function jobHtml(m) {
  if (!m.live) return "";
  const f = jobFacts(m);
  return `
    <section class="job">
      <div class="job-count"><span class="num" data-job="count">${escHtml(f.count)}</span> <span class="unit" data-job="unit">${f.unit}</span></div>
      <dl class="job-facts">
        <div class="from"><dt>From</dt><dd><span class="lamp busy"></span><span class="job-device" data-job="device" title="${escHtml(f.device)}">${escHtml(f.device)}</span></dd></div>
        <div><dt>Received</dt><dd class="mono" data-job="bytes">${escHtml(f.bytes)}</dd></div>
        <div><dt>Speed</dt><dd class="mono" data-job="speed">${escHtml(f.speed)}</dd></div>
        <div class="wide"><dt data-job="lastLabel">${escHtml(f.lastLabel)}</dt><dd class="mono" data-job="last" title="${escHtml(f.last === "-" ? "" : f.last)}">${escHtml(f.last)}</dd></div>
      </dl>
      <div class="job-progress"${f.progress ? "" : " hidden"}>
        <div class="job-bar" role="progressbar" aria-label="Upload progress" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${f.progress?.pct ?? 0}"><span class="fill" style="transform:scaleX(${(f.progress?.pct ?? 0) / 100})"></span>${TICKS}</div>
        <span class="mono job-progress-text" data-job="progressText">${escHtml(progressText(f.progress))}</span>
      </div>
    </section>`;
}

/**
 * Show, update or remove the envelope. It is built only when a session starts, so its entrance and
 * the busy lamp play once; later ticks only change its words.
 */
function patchJob(m) {
  const slot = document.querySelector("#job-slot");
  if (!slot) return;
  const job = slot.querySelector(".job");
  if (!m.live) {
    if (job) slot.innerHTML = "";
  } else if (!job) {
    slot.innerHTML = jobHtml(m);
  } else {
    const f = jobFacts(m);
    for (const key of ["device", "count", "unit", "bytes", "speed", "lastLabel", "last"]) {
      const el = job.querySelector(`[data-job="${key}"]`);
      if (el && el.textContent !== f[key]) el.textContent = f[key];
    }
    const device = job.querySelector('[data-job="device"]');
    if (device) device.title = f.device;
    const last = job.querySelector('[data-job="last"]');
    if (last) last.title = f.last === "-" ? "" : f.last;
    const prog = job.querySelector(".job-progress");
    if (prog) {
      prog.hidden = !f.progress;
      const bar = prog.querySelector(".job-bar");
      const fill = bar?.querySelector(".fill");
      if (bar && fill && f.progress) {
        bar.setAttribute("aria-valuenow", String(f.progress.pct));
        fill.style.transform = `scaleX(${f.progress.pct / 100})`;
      }
      const text = prog.querySelector('[data-job="progressText"]');
      const t = progressText(f.progress);
      if (text && text.textContent !== t) text.textContent = t;
    }
  }
  announceJob(m);
}

/** Polite, once per change: "Receiving from Pixel 8" when a phone starts, "Done..." when it goes quiet. */
let wasLive = false;
function announceJob(m) {
  const out = document.querySelector("#job-status");
  if (!out || m.live === wasLive) return;
  wasLive = m.live;
  const s = state.session;
  if (m.live) out.textContent = `Receiving from ${sender(m)}`;
  else if (!m.stopped && s) out.textContent = `Done. ${plural(s.count, "file")} received from ${s.device || "your phone"}.`;
}

function sheetHtml(m) {
  if (m.loading) {
    return `<div class="sheet" aria-busy="true">${Array.from({ length: 8 }, () =>
      `<div class="frame is-loading"><span class="edge"></span><span class="shot"></span><span class="edge"></span></div>`).join("")}</div>`;
  }
  if (!m.shown.length) {
    return emptyHtml({
      title: "Nothing has arrived yet",
      body: m.address
        ? "Scan the ticket with Pherry on your phone. Photos land here as they arrive, and in your Pherry folder on disk."
        : "Once this computer is on a network, scan the ticket with Pherry on your phone.",
    });
  }
  const total = Number(m.s.totalReceived || m.items.length);
  return `<div class="sheet" id="arrivals-sheet">${m.shown.map((e, i) =>
    frameHtml(e, { number: total - i, edgeRight: fmtClock(entryTime(e)), edgeBottom: e.bucketName || "" })).join("")}</div>`;
}

function arrivalsHead(m) {
  const isToday = m.today.length > 0;
  const tally = isToday
    ? `${m.todayCapped ? `${n(m.today.length)}+` : n(m.today.length)} ${m.today.length === 1 ? "file" : "files"} · ${fmtBytes(m.todayBytes)}`
    : m.items.length ? "Nothing new today" : "";
  return `
    <div class="arrivals-head">
      <div>
        <h2 class="section-title">${isToday || !m.items.length ? "Arrived today" : "Latest arrivals"}</h2>
        ${tally ? `<div class="tally mono" id="arrivals-tally">${escHtml(tally)}</div>` : ""}
      </div>
      ${m.items.length ? `<button class="btn quiet sm" id="see-photos">All photos${icon("arrow-right", { size: 15 })}</button>` : ""}
    </div>`;
}

function ledgerHtml(m) {
  if (m.loading || m.stopped) return "";
  return `
    <p class="ledger-line mono">
      <span>All time ${plural(Number(m.s.totalReceived || 0), "file")} · ${escHtml(fmtBytes(m.s.totalBytes || 0))}</span>
      <span>Running ${escHtml(fmtUptime(m.s.uptimeMs || 0))}</span>
    </p>`;
}

function render() {
  const m = model();
  document.querySelector("#view-root").innerHTML = `
    <header class="view-head">
      <div>
        <h1 class="view-title" id="receiver-title">${escHtml(headline(m))}</h1>
        <div class="view-sub">${escHtml(computerName())}${m.address ? ` · ${escHtml(m.address)}` : ""}</div>
      </div>
      <div class="view-actions">
        <button class="btn" id="open-folder">${icon("folder-open", { size: 17 })}Open folder</button>
      </div>
    </header>
    ${m.stopped ? `
      <div class="notice" role="alert">${icon("warning-circle", { size: 20 })}
        <div class="notice-text">Pherry isn't receiving.<span>${escHtml(state.server.error ? `Port ${m.port} couldn't be opened (${state.server.error}).` : "The receiver is off.")} Pick another port in Settings.</span></div>
        <button class="btn danger sm" id="fix-port">Change port</button>
      </div>` : ""}
    <div class="receiver">
      ${ticketHtml(m)}
      <div class="arrivals">
        <div class="visually-hidden" id="job-status" role="status" aria-live="polite"></div>
        <div id="job-slot">${jobHtml(m)}</div>
        ${arrivalsHead(m)}
        <div id="sheet-slot">${sheetHtml(m)}</div>
        <div id="ledger-slot">${ledgerHtml(m)}</div>
      </div>
    </div>`;

  wasLive = m.live; // a full render starts settled: only later changes are announced
  if (m.address) {
    const canvas = document.querySelector("#pair-qr");
    const payload = qrPayloadFor(m.ip, m.port, m.token);
    if (canvas && payload) renderQrCode(canvas, payload);
  }
  wire(m);
}

/** What forces a full render: anything the ticket or the notice shows. */
function signature(m) {
  return [m.loading, m.stopped, m.address, m.token, state.server.error, sortedIPs(state.ips).join(",")].join("|");
}
let lastSig = "";

/** Live refresh without rebuilding the ticket, so focus and hover survive a busy backup. */
function patch() {
  const m = model();
  const title = document.querySelector("#receiver-title");
  if (!title || signature(m) !== lastSig) return render();
  if (title.textContent !== headline(m)) title.textContent = headline(m);
  patchJob(m);
  // One yellow field per screen: while a phone sends, the job band holds it and the stub goes quiet.
  document.querySelector(".ticket")?.classList.toggle("is-quiet", m.live);
  document.querySelector("#ledger-slot").innerHTML = ledgerHtml(m);
  const sheet = document.querySelector("#arrivals-sheet");
  const keys = m.shown.map(entryKey).join("|");
  if (!sheet || sheet.dataset.keys !== keys) {
    patchHead(m);
    patchSheet(m, keys);
  }
}

/**
 * Rebuild the sheet in a scratch tree and carry the frames already on screen over into it, so
 * thumbnails don't reload and a frame mid-develop keeps its clock. Keyboard focus on a frame
 * follows it by key (moving a node drops focus), or falls back to the nearest sensible control.
 */
function patchSheet(m, keys) {
  const slot = document.querySelector("#sheet-slot");
  if (!slot) return;
  const active = document.activeElement;
  const focusKey = slot.contains(active) ? active.closest?.(".frame[data-key]")?.dataset.key || "" : "";
  const previous = new Map();
  slot.querySelectorAll(".frame[data-key]").forEach((el) => previous.set(el.dataset.key, el));

  const scratch = document.createElement("div");
  scratch.innerHTML = sheetHtml(m);
  wireFrames(scratch); // only the new copies; frames carried over are already wired
  reuseFrames(scratch, previous);
  slot.replaceChildren(...scratch.childNodes);
  const fresh = slot.querySelector("#arrivals-sheet");
  if (fresh) fresh.dataset.keys = keys;
  attachThumbs(slot);

  if (focusKey && !slot.contains(document.activeElement)) {
    const frames = [...slot.querySelectorAll(".frame[data-key]")];
    const target = frames.find((f) => f.dataset.key === focusKey)
      || document.querySelector("#see-photos") || frames[0];
    target?.focus({ preventScroll: true });
  }
}

/** Update the arrivals heading in place; #see-photos (and its focus) is only added or removed. */
function patchHead(m) {
  const head = document.querySelector(".arrivals-head");
  const next = htmlToNode(arrivalsHead(m));
  if (!head || !next) return;
  const textBox = head.firstElementChild;
  const nextText = next.firstElementChild;
  if (textBox && nextText && textBox.innerHTML !== nextText.innerHTML) textBox.replaceWith(nextText);
  const see = head.querySelector("#see-photos");
  const nextSee = next.querySelector("#see-photos");
  if (see && !nextSee) see.remove();
  else if (!see && nextSee) {
    head.appendChild(nextSee);
    nextSee.addEventListener("click", () => navigate("photos"));
  }
}

function htmlToNode(html) {
  const t = document.createElement("template");
  t.innerHTML = html.trim();
  return t.content.firstElementChild;
}

function wireSheet() {
  const root = document.querySelector("#sheet-slot");
  if (!root) return;
  wireFrames(root);
  attachThumbs(root);
}

function wire(m) {
  lastSig = signature(m);
  const sheet = document.querySelector("#arrivals-sheet");
  if (sheet) sheet.dataset.keys = m.shown.map(entryKey).join("|");
  wireSheet();
  document.querySelector("#open-folder")?.addEventListener("click", () => window.api.openFolder());
  document.querySelector("#see-photos")?.addEventListener("click", () => navigate("photos"));
  document.querySelector("#go-settings")?.addEventListener("click", () => navigate("settings"));
  document.querySelector("#fix-port")?.addEventListener("click", () => navigate("settings"));
  const copy = async (text, btn) => {
    try {
      await navigator.clipboard.writeText(text);
      showToast(`Copied ${text}`);
      if (btn) {
        const label = btn.querySelector("span");
        if (label) { label.textContent = "Copied"; setTimeout(() => { label.textContent = "Copy"; }, 1400); }
      }
    } catch {
      showToast("Couldn't copy. Select the address and copy it by hand.", "error");
    }
  };
  document.querySelector("#copy-address")?.addEventListener("click", (e) => copy(m.address, e.currentTarget));
  document.querySelectorAll("[data-copy]").forEach((b) => b.addEventListener("click", () => copy(b.dataset.copy)));
}

// Entering the view renders it; while it is on screen, ticks and arrivals patch it in place.
register("receiver", () => {
  if (document.querySelector("#receiver-title")) patch();
  else render();
});
export { render as renderReceiver };
