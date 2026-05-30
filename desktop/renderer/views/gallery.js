import { state } from '../state.js';
import {
  escHtml, entryName, entryTime,
  fmtBytes, fmtFullTime, fileIcon, isVideo,
} from '../utils.js';
import { register } from '../router.js';
import { showToast } from '../shell.js';
import { attachThumbs, canThumbnail } from '../thumbs.js';

function isMedia(name) {
  return isVideo(name) || /\.(heic|heif|jpe?g|png|gif|webp|tif?f|bmp)$/i.test(name || "");
}

function dayLabel(ts) {
  if (!ts) return "Earlier";
  const d = new Date(ts);
  const now = new Date();
  const sameDay = (a, b) =>
    a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
  const yest = new Date(now);
  yest.setDate(now.getDate() - 1);
  if (sameDay(d, now)) return "Today";
  if (sameDay(d, yest)) return "Yesterday";
  return d.toLocaleDateString(undefined, { weekday: "short", year: "numeric", month: "long", day: "numeric" });
}

function groupByDay(items) {
  const groups = new Map();
  for (const entry of items) {
    const label = dayLabel(entryTime(entry));
    if (!groups.has(label)) groups.set(label, []);
    groups.get(label).push(entry);
  }
  return [...groups.entries()];
}

function cell(entry) {
  const name = entryName(entry);
  const video = isVideo(name);
  const thumb = canThumbnail(name)
    ? ` data-thumb-bucket="${escHtml(entry.bucketName || "")}" data-thumb-name="${escHtml(name)}"`
    : "";
  return `
    <button class="gallery-cell${video ? " video" : ""}" data-bucket="${escHtml(entry.bucketName || "")}" data-name="${escHtml(name)}" title="${escHtml(name)} / ${escHtml(fmtFullTime(entryTime(entry)))}">
      <div class="gallery-thumb"${thumb}><span class="icon">${fileIcon(name)}</span></div>
      ${video ? `<span class="gallery-play"><span class="icon sm">play_arrow</span></span>` : ""}
      <span class="gallery-cap">${escHtml(fmtBytes(entry.size))}</span>
    </button>
  `;
}

function renderGallery() {
  document.querySelector("#page-title").textContent = "Gallery";
  document.querySelector("#page-tag").hidden = true;

  const all = (state.history.items || []).filter((e) => isMedia(entryName(e)));
  const total = state.history.totalCount || all.length;
  const groups = groupByDay(all);

  document.querySelector("#view-root").innerHTML = `
    <div class="view-stack">
      <div class="toolbar">
        <div>
          <h2>Received media</h2>
          <div class="count">${all.length.toLocaleString()} loaded photo${all.length === 1 ? "" : "s"} & video${all.length === 1 ? "" : "s"} of ${total.toLocaleString()} transfers</div>
        </div>
        <button class="btn" id="gallery-open-folder"><span class="icon sm">folder_open</span>Open folder</button>
      </div>

      ${all.length === 0 ? `
        <div class="empty"><span class="icon">photo_library</span><strong>No media yet</strong>Photos and videos you receive will appear here, grouped by day.</div>
      ` : groups.map(([label, items]) => `
        <section class="gallery-day">
          <div class="gallery-day-head">
            <span>${escHtml(label)}</span>
            <span class="count">${items.length.toLocaleString()}</span>
          </div>
          <div class="gallery-grid">
            ${items.map(cell).join("")}
          </div>
        </section>
      `).join("")}
    </div>
  `;

  document.querySelector("#gallery-open-folder")?.addEventListener("click", () => window.api.openFolder());

  document.querySelectorAll(".gallery-cell").forEach((el) => {
    el.addEventListener("click", async () => {
      const ok = await window.api.openFile({ bucket: el.dataset.bucket, name: el.dataset.name });
      if (!ok) showToast("That file is no longer in the download folder.", "error");
    });
    el.addEventListener("contextmenu", (e) => {
      e.preventDefault();
      window.api.revealFile({ bucket: el.dataset.bucket, name: el.dataset.name });
    });
  });

  attachThumbs(document.querySelector("#view-root"));
}

register("gallery", renderGallery);
export { renderGallery };
