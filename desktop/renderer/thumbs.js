// Lazy real-thumbnail loader. The main process turns a received file into a small data-URL
// thumbnail (OS-cached). We fetch them lazily as the placeholder scrolls into view, so even a
// long history table only thumbnails what's visible.

const inflight = new Map(); // key -> Promise<dataUrl|null>
const resolved = new Map(); // key -> dataUrl (kept so re-renders are instant)

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
      const el = entry.target;
      observer.unobserve(el);
      hydrate(el);
    }
  }, { rootMargin: "200px" });
  return observer;
}

async function hydrate(el) {
  const bucket = el.dataset.thumbBucket;
  const name = el.dataset.thumbName;
  if (!name) return;
  const dataUrl = await loadThumb(bucket, name);
  if (!dataUrl) return; // leave the icon fallback in place
  el.style.backgroundImage = `url("${dataUrl}")`;
  el.classList.add("has-thumb");
  const icon = el.querySelector(".icon");
  if (icon) icon.style.opacity = "0";
}

// Call after each render. Any element with data-thumb-name gets a lazily-loaded thumbnail.
export function attachThumbs(root = document) {
  const els = root.querySelectorAll("[data-thumb-name]:not([data-thumb-bound])");
  const io = ensureObserver();
  els.forEach((el) => {
    el.setAttribute("data-thumb-bound", "1");
    const key = keyFor(el.dataset.thumbBucket, el.dataset.thumbName);
    if (resolved.has(key) && resolved.get(key)) {
      hydrate(el); // already cached — paint immediately
    } else {
      io.observe(el);
    }
  });
}
