// Shared markup for the contact sheet and other repeated pieces of the Pherry desktop world.
import { state } from './state.js';
import { escHtml, entryName, entryTime, entryKey, isVideo, fileIcon, fmtBytes, fmtFullTime } from './utils.js';
import { canThumbnail } from './thumbs.js';
import { icon } from './icons.js';
import { showToast } from './shell.js';

/**
 * One frame on a contact sheet: edge print above (frame number, left; `edgeRight` on the right),
 * the picture, and a second edge line below (`edgeBottom`). A frame that arrived while the window
 * was open develops from a negative into the print, once. Until its thumbnail paints the frame is
 * blank film; the file glyph shows only where no picture is coming (`.no-thumb`, see thumbs.js).
 */
export function frameHtml(entry, { number, edgeRight = "", edgeBottom = "" } = {}) {
  const name = entryName(entry);
  const key = entryKey(entry);
  const elapsed = developElapsed(key);
  const developing = elapsed != null;
  const thumbable = canThumbnail(name);
  const thumb = thumbable
    ? ` data-thumb-bucket="${escHtml(entry.bucketName || "")}" data-thumb-name="${escHtml(name)}"`
    : "";
  const label = `${name}, ${fmtBytes(entry.size)}, saved ${fmtFullTime(entryTime(entry))}. Open.`;
  return `
    <button class="frame${developing ? " developing" : ""}"${developing ? ` style="--develop-at:-${Math.round(elapsed)}ms"` : ""} data-bucket="${escHtml(entry.bucketName || "")}" data-name="${escHtml(name)}" data-key="${escHtml(key)}" title="${escHtml(name)}" aria-label="${escHtml(label)}">
      <span class="edge"><span>${number != null ? `${escHtml(String(number))}${icon("caret-right", { weight: "bold", size: 9 })}` : ""}</span><span class="dim">${escHtml(edgeRight)}</span></span>
      <span class="shot${thumbable ? "" : " no-thumb"}"${thumb}>${icon(fileIcon(name), { size: 26 })}${isVideo(name) ? `<span class="play">${icon("play", { weight: "fill", size: 12 })}</span>` : ""}</span>
      <span class="edge"><span class="dim">${escHtml(edgeBottom)}</span></span>
    </button>`;
}

/** Open on click, reveal in the folder on right-click. */
export function wireFrames(root) {
  pruneFresh();
  root.querySelectorAll(".frame[data-name]").forEach((el) => {
    el.addEventListener("click", async () => {
      const ok = await window.api.openFile({ bucket: el.dataset.bucket, name: el.dataset.name });
      if (!ok) showToast("That file is no longer in the Pherry folder.", "error");
    });
    el.addEventListener("contextmenu", async (e) => {
      e.preventDefault();
      const ok = await window.api.revealFile({ bucket: el.dataset.bucket, name: el.dataset.name });
      if (!ok) showToast("That file is no longer in the Pherry folder.", "error");
    });
  });
}

// ── Develop timing ──────────────────────────────────────────────────────────
// A fresh frame develops once, on the clock of its arrival. A frame rebuilt (or moved) mid-develop
// picks the animation up where it was through a negative --develop-at delay instead of replaying it.
const DEVELOP_MS = 1600;
const FADE_MS = 300;
const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");

function developMs() { return reducedMotion.matches ? FADE_MS : DEVELOP_MS; }

/** Milliseconds since this key arrived while it is still developing, else null. */
export function developElapsed(key) {
  const t = state.fresh.get(key);
  if (t == null) return null;
  const elapsed = performance.now() - t;
  return elapsed < developMs() ? Math.max(0, elapsed) : null;
}

/** Forget arrivals that have finished developing, whether or not their frame was ever drawn. */
export function pruneFresh() {
  const now = performance.now();
  for (const [key, t] of state.fresh) if (now - t >= DEVELOP_MS) state.fresh.delete(key);
}

/** Bring a frame carried over from an earlier render up to date with its develop clock. */
function syncDevelop(el) {
  const elapsed = developElapsed(el.dataset.key);
  if (elapsed == null) {
    el.classList.remove("developing");
    el.style.removeProperty("--develop-at");
  } else {
    el.classList.add("developing");
    el.style.setProperty("--develop-at", `-${Math.round(elapsed)}ms`);
  }
}

/**
 * Swap frames already on screen in for their new copies in `fresh` (a detached, already wired
 * scratch tree), so a busy backup doesn't reload every thumbnail or rewire every frame. A frame is
 * carried over only while its edge print still matches. `previous` maps data-key to the old frame.
 */
export function reuseFrames(fresh, previous) {
  if (!previous.size) return;
  fresh.querySelectorAll(".frame[data-key]").forEach((el) => {
    const old = previous.get(el.dataset.key);
    if (!old || old === el) return;
    if (old.querySelector(".edge")?.textContent !== el.querySelector(".edge")?.textContent) return;
    syncDevelop(old);
    el.replaceWith(old);
  });
}

/** An unexposed strip: the empty state's picture. */
export function emptyHtml({ title, body, action = "" }) {
  return `
    <div class="empty">
      <div class="empty-strip" aria-hidden="true"><span></span><span></span><span></span><span></span></div>
      <h2>${escHtml(title)}</h2>
      <p>${body}</p>
      ${action}
    </div>`;
}
