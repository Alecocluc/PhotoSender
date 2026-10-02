// Lazy real-thumbnail loader. The main process turns a received file into a small data-URL
// thumbnail (OS-cached). We fetch them lazily as the frame scrolls into view, so even a long
// history only thumbnails what's visible. The image is placed inside the target as an <img> so
// the develop animation and object-fit apply to it. While it loads the target is blank film; when
// no picture can come (no thumbnail, or one that won't decode) the target gets `.no-thumb`, which
// shows its file glyph (styles.css).

const inflight = new Map(); // key -> Promise<dataUrl|null>
const resolved = new Map(); // key -> dataUrl, or null for a failure (kept so re-renders are instant)

const IMAGE_RE = /\.(jpe?g|png|gif|webp|bmp|heic|heif|tiff?)$/i;

export function canThumbnail(name) {
  return IMAGE_RE.test(name || "");
}

function keyFor(bucket, name) {
  return `${bucket || ""}/${name || ""}`;
}

async function loadThumb(bucket, name) {
  const key = keyFor(bucket, name);
  if (resolved.has(key)) return resolved.get(key);
  if (inflight.has(key)) return inflight.get(key);
  const p = (async () => {
    try {
      const dataUrl = await window.api.getThumbnail({ bucket, name });
      resolved.set(key, dataUrl || null);
      return dataUrl || null;
    } catch {
      resolved.set(key, null);
      return null;
    } finally {
      inflight.delete(key);
    }
  })();
  inflight.set(key, p);
  return p;
}

let observer = null;
function ensureObserver() {
  if (observer) return observer;
  observer = new IntersectionObserver((entries) => {
    for (const entry of entries) {
      if (!entry.isIntersecting) continue;
      observer.unobserve(entry.target);
      hydrate(entry.target);
    }
  }, { rootMargin: "300px" });
  return observer;
}

async function hydrate(el) {
  const name = el.dataset.thumbName;
  if (!name) return;
  const key = keyFor(el.dataset.thumbBucket, name);
  const dataUrl = await loadThumb(el.dataset.thumbBucket, name);
  if (!el.isConnected) return;
  // No thumbnail (moved, deleted, unsupported codec): swap the blank film for the file glyph.
  if (!dataUrl) { el.classList.add("no-thumb"); return; }
  const img = document.createElement("img");
  img.alt = "";
  img.decoding = "async";
  img.addEventListener("load", () => {
    el.querySelector(":scope > .ic")?.remove();
    el.classList.add("has-thumb");
  }, { once: true });
  // A picture that won't decode fails the same way, and is remembered so re-renders skip the wait.
  img.addEventListener("error", () => {
    img.remove();
    resolved.set(key, null);
    el.classList.add("no-thumb");
  }, { once: true });
  img.src = dataUrl;
  el.prepend(img);
}

/** Call after each render. Any element with data-thumb-name gets a lazily-loaded thumbnail. */
export function attachThumbs(root = document) {
  const io = ensureObserver();
  root.querySelectorAll("[data-thumb-name]:not([data-thumb-bound])").forEach((el) => {
    el.setAttribute("data-thumb-bound", "1");
    // Already settled: paint the picture now, or the glyph for a known failure.
    if (resolved.has(keyFor(el.dataset.thumbBucket, el.dataset.thumbName))) hydrate(el);
    else io.observe(el);
  });
}
