import { ByteLRU } from './cache.js';
import { state } from './state.js';

const resolved = new ByteLRU(24 * 1024 * 1024);
const inflight = new Map(), observed = new Set(), queue = [];
const MAX_ACTIVE = 3;
let active = 0, generation = 0;
const MEDIA_RE = /\.(jpe?g|png|gif|webp|bmp|heic|heif|tiff?|mp4|mov|avi|mkv|webm|m4v|3gp)$/i;
export function canThumbnail(name) { return MEDIA_RE.test(name || ''); }
function optsFor(el) {
  return { bucket: el.dataset.thumbBucket, name: el.dataset.thumbName,
    relativePath: el.dataset.thumbPath || '', deviceId: el.dataset.thumbDevice || '' };
}
function keyFor(opts) {
  return JSON.stringify([state.status?.libraryId || state.settings?.downloadPath || '',
    opts.deviceId || '', opts.relativePath || '', opts.bucket || '', opts.name || '']);
}
function pump() {
  if (document.hidden) return;
  while (active < MAX_ACTIVE && queue.length) {
    const task = queue.shift();
    if (task.generation !== generation || ![...task.elements].some((el) => el.isConnected)) {
      if (inflight.get(task.key) === task) inflight.delete(task.key);
      task.resolve(undefined); continue;
    }
    active += 1;
    Promise.resolve().then(() => window.api.getThumbnail(task.opts)).then((data) => {
      if (task.generation === generation) {
        const value = data || null;
        resolved.set(task.key, { data: value, until: value ? Infinity : Date.now() + 30000 }, value ? value.length * 2 : 128);
        task.resolve(value);
      } else task.resolve(undefined);
    }, () => task.resolve(null)).finally(() => {
      active -= 1;
      if (inflight.get(task.key) === task) inflight.delete(task.key);
      pump();
    });
  }
}
function request(el, opts) {
  const key = keyFor(opts), cached = resolved.get(key);
  if (cached && cached.until > Date.now()) return Promise.resolve(cached.data);
  if (cached) resolved.delete(key);
  let task = inflight.get(key);
  if (task) { task.elements.add(el); return task.promise; }
  task = { key, opts, generation, elements: new Set([el]) };
  task.promise = new Promise((resolve) => { task.resolve = resolve; });
  inflight.set(key, task); queue.push(task); pump();
  return task.promise;
}
async function hydrate(el) {
  if (document.hidden) return;
  const epoch = generation, opts = optsFor(el);
  const dataUrl = await request(el, opts);
  if (!el.isConnected || epoch !== generation || dataUrl === undefined) return;
  if (!dataUrl) { el.classList.add('no-thumb'); return; }
  if (el.querySelector('img')) return;
  const img = document.createElement('img');
  img.alt = ''; img.decoding = 'async';
  img.addEventListener('load', () => { el.querySelector(':scope > .ic')?.remove(); el.classList.add('has-thumb'); }, { once: true });
  img.addEventListener('error', () => {
    img.remove(); el.classList.add('no-thumb');
    resolved.set(keyFor(opts), { data: null, until: Date.now() + 30000 }, 128);
  }, { once: true });
  img.src = dataUrl; el.prepend(img);
}
const observer = new IntersectionObserver((entries) => {
  for (const { target, isIntersecting } of entries) {
    if (!isIntersecting || document.hidden) continue;
    observer.unobserve(target); observed.delete(target); hydrate(target);
  }
}, { rootMargin: '160px' });

export function attachThumbs(root = document) {
  for (const el of observed) if (!el.isConnected) { observer.unobserve(el); observed.delete(el); }
  root.querySelectorAll('[data-thumb-name]:not([data-thumb-bound])').forEach((el) => {
    el.dataset.thumbBound = '1';
    observed.add(el); observer.observe(el);
  });
}
export function detachThumbs(root) {
  if (!root) return;
  for (const el of observed) if (root.contains(el)) {
    observer.unobserve(el); observed.delete(el); el.removeAttribute('data-thumb-bound');
  }
}
export function clearThumbnails() {
  generation += 1; resolved.clear();
  for (const task of queue.splice(0)) {
    if (inflight.get(task.key) === task) inflight.delete(task.key);
    task.resolve(undefined);
  }
  inflight.clear();
  document.querySelectorAll('[data-thumb-bound]').forEach((el) => {
    el.removeAttribute('data-thumb-bound');
    el.querySelector('img')?.remove(); el.classList.remove('has-thumb', 'no-thumb');
  });
  attachThumbs();
}
document.addEventListener('visibilitychange', () => {
  if (document.hidden) {
    // Pending work has not reached the OS. Drop it; visible nodes can be observed again on return.
    for (const task of queue.splice(0)) {
      if (inflight.get(task.key) === task) inflight.delete(task.key);
      for (const el of task.elements) el.removeAttribute('data-thumb-bound');
      task.resolve(undefined);
    }
  } else {
    for (const el of observed) { observer.unobserve(el); observer.observe(el); }
    attachThumbs(); pump();
  }
});
