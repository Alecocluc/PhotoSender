import { state } from './state.js';
import { DataWindow, visibleRange } from './data-window.js';
import { escHtml, entryName, entryTime, entryKey, entryKind, fileArgs, fileIcon, fmtBytes, fmtFullTime, fmtStampDate, n } from './utils.js';
import { frameHtml, reuseFrames, reuseRowThumbnails } from './components.js';
import { attachThumbs, detachThumbs, canThumbnail } from './thumbs.js';
import { icon } from './icons.js';
import { showToast } from './shell.js';
import { clearHistoryConfirm, exportHistory } from './actions.js';

const EMPTY_QUERY = { query: '', kind: 'all', album: '', deviceId: '', sort: 'newest', dateFrom: '', dateTo: '' };
const SORTS = [['newest', 'Newest first'], ['oldest', 'Oldest first'], ['largest', 'Largest first'], ['name', 'By name']];
const safe = escHtml;
function options(items, value) {
  return items.map(([key, label]) => `<option value="${safe(key)}"${key === value ? ' selected' : ''}>${safe(label)}</option>`).join('');
}
function identity(device) { return String(device.deviceId || device.id || ''); }

/**
 * A persistent inventory and a transfer ledger share this presentation, never their data.
 * Only the visible rows and two overscan rows exist in the DOM; six 120-item pages stay in memory.
 */
export function createMediaBrowser({ mode, title, query: preferences }) {
  const grid = mode === 'photos';
  const query = Object.assign({}, EMPTY_QUERY, preferences);
  const root = document.querySelector('#view-root'), main = document.querySelector('#main');
  let disposed = false, frame = 0, timer = 0, activeIndex = 0, pendingFocus = null;
  let lastRevision = state.revision, lastEpoch = state.collectionEpoch, lastPaint = '', lastCount = '', facets = '';
  let columns = 1, rowHeight = 78;
  const store = new DataWindow((args) => grid ? window.api.getMedia(args) : window.api.getHistory(args), schedule);
  const filterId = (key) => `${mode}-${key}`;
  root.innerHTML = `
    <header class="view-head">
      <div><h1 class="view-title" id="${mode}-title">${title}</h1>
      <div class="view-sub" id="browser-sub">${grid ? 'Every photo and video saved in your Pherry folder' : 'The newest transfers, with a permanent library in Photos'}</div></div>
      <div class="view-actions">
        ${grid ? `<button class="btn" id="browser-open">${icon('folder-open', { size: 17 })}Open folder</button>` :
          `<button class="btn quiet" id="history-export">${icon('download-simple', { size: 17 })}Export</button>
          <button class="btn danger" id="history-clear">${icon('trash', { size: 17 })}Clear</button>`}
      </div>
    </header>
    <section class="media-browser ${grid ? 'is-grid' : 'is-ledger'}" aria-labelledby="${mode}-title">
      <div class="query-controls">
        <label class="search query-search">${icon('magnifying-glass', { size: 17 })}
          <span class="visually-hidden">Search all ${grid ? 'saved files' : 'transfers'}</span>
          <input id="${filterId('query')}" type="search" placeholder="Search file, album or phone" value="${safe(query.query)}" autocomplete="off" spellcheck="false" />
        </label>
        <select class="select" id="${filterId('kind')}" aria-label="File type">${options([['all', 'All types'], ['photo', 'Photos'], ['video', 'Videos'], ...(!grid ? [['other', 'Other']] : [])], query.kind)}</select>
        <select class="select" id="${filterId('deviceId')}" aria-label="Phone"><option value="">All phones</option></select>
        <select class="select" id="${filterId('album')}" aria-label="Album"><option value="">All albums</option></select>
        <select class="select" id="${filterId('sort')}" aria-label="Sort order">${options(SORTS, query.sort)}</select>
        <details class="query-dates"><summary>Date saved</summary><div>
          <label>From<input class="input" id="${filterId('dateFrom')}" type="date" value="${safe(query.dateFrom)}" /></label>
          <label>Through<input class="input" id="${filterId('dateTo')}" type="date" value="${safe(query.dateTo)}" /></label>
        </div></details>
        <button class="btn quiet sm" id="browser-reset">Clear filters</button>
      </div>
      <p class="query-error" id="query-error" role="alert" hidden></p>
      <div class="guide browser-guide">
        <span id="browser-count" role="status" aria-live="polite">Loading…</span>
        <button class="link" id="browser-refresh" hidden>Updates available · Refresh</button>
        <form class="browser-jump" id="browser-jump"><label for="browser-index">Go to item</label>
          <input class="input mono" type="number" id="browser-index" min="1" value="1" aria-label="Item number" />
          <button class="btn quiet sm" type="submit">Go</button>
        </form>
      </div>
      <p class="browser-error" id="browser-error" role="alert" hidden><span></span> <button class="btn sm" id="browser-retry">Try again</button></p>
      <div class="browser-empty" id="browser-empty" hidden><div class="empty-strip" aria-hidden="true"><span></span><span></span><span></span><span></span></div>
        <h2>Nothing here yet</h2><p></p></div>
      <p class="visually-hidden" id="browser-keys">Arrow keys move between files. Home and End move to the first and last file. Enter opens a file. Shift F10 shows it in its folder.</p>
      <div class="media-viewport" id="media-viewport" role="list" aria-label="${title}" aria-describedby="browser-keys">
        <div class="media-window" id="media-window"></div>
      </div>
      <p class="browser-foot" id="browser-foot">${grid ? 'Your files stay in their phone and album folders. Click a frame to open it; right-click to show it in its folder.' : 'History records transfer events. Clearing it leaves the files and the Photos library on disk.'}</p>
    </section>`;
  const viewport = root.querySelector('#media-viewport'), windowEl = root.querySelector('#media-window');
  const errorEl = root.querySelector('#browser-error'), emptyEl = root.querySelector('#browser-empty');

  function schedule() {
    if (disposed || frame) return;
    frame = requestAnimationFrame(() => { frame = 0; paint(); });
  }
  function dimensions() {
    columns = grid ? Math.max(1, Math.floor(viewport.clientWidth / (window.innerWidth <= 600 ? 128 : 160))) : 1;
    rowHeight = grid ? Math.ceil((viewport.clientWidth / columns - 10) * 0.75 + 36 + 14) : (window.innerWidth <= 820 ? 104 : 78);
  }
  function scrollOffset() { return main.scrollTop + viewport.getBoundingClientRect().top - main.getBoundingClientRect().top; }
  function range() {
    dimensions();
    return visibleRange({ total: store.ready ? store.total : 12, columns, rowHeight,
      scrollTop: main.scrollTop - scrollOffset(), viewportHeight: main.clientHeight, overscan: 2 });
  }
  function updateFacets() {
    const albums = store.meta.albums || store.meta.facets?.albums || [];
    const devices = store.meta.devices || store.meta.facets?.devices || state.devices;
    const fingerprint = JSON.stringify([albums, devices]);
    if (fingerprint === facets) return;
    facets = fingerprint;
    const phoneOptions = [['', 'All phones'], ...devices.map((d) => [identity(d), d.deviceName || d.name || 'Unnamed phone'])];
    const albumOptions = [['', 'All albums'], ...albums.map((a) => typeof a === 'string' ? [a, a || 'Unsorted'] : [a.bucketName || a.name || a.album, a.bucketName || a.name || a.album])];
    if (query.deviceId && !phoneOptions.some(([id]) => id === query.deviceId)) phoneOptions.push([query.deviceId, 'Selected phone']);
    if (query.album && !albumOptions.some(([id]) => id === query.album)) albumOptions.push([query.album, query.album]);
    root.querySelector('#' + filterId('deviceId')).innerHTML = options(phoneOptions, query.deviceId);
    root.querySelector('#' + filterId('album')).innerHTML = options(albumOptions, query.album);
  }
  function rowHtml(entry, index) {
    if (!entry) return `<div class="media-placeholder ${grid ? 'frame' : ''}" aria-hidden="true" data-index="${index}"><span></span></div>`;
    const name = entryName(entry), file = fileArgs(entry), key = entryKey(entry);
    if (grid) return `<div class="media-cell" role="listitem" aria-posinset="${index + 1}" aria-setsize="${store.total}" data-index="${index}">
      ${frameHtml(entry, { number: index + 1, edgeRight: fmtStampDate(entryTime(entry)), edgeBottom: [entry.deviceName, entry.bucketName].filter(Boolean).join(' / ') })}</div>`;
    const thumb = canThumbnail(name) ? ` data-thumb-name="${safe(file.name)}" data-thumb-bucket="${safe(file.bucket)}" data-thumb-path="${safe(file.relativePath)}" data-thumb-device="${safe(file.deviceId)}"` : '';
    return `<div class="inventory-row" role="listitem" aria-posinset="${index + 1}" aria-setsize="${store.total}" data-index="${index}" data-key="${safe(key)}">
      <button class="inventory-entry" data-act="open" tabindex="${index === activeIndex ? 0 : -1}" aria-label="Open ${safe(name)}, ${safe(entry.deviceName || 'Unknown phone')}, ${safe(entry.bucketName || 'Unsorted')}, ${safe(fmtBytes(entry.size))}, saved ${safe(fmtFullTime(entryTime(entry)))}">
        <span class="inventory-no mono">${n(index + 1)}</span>
        <span class="mini"><span class="shot${thumb ? '' : ' no-thumb'}"${thumb}>${icon(fileIcon(name), { size: 18 })}</span></span>
        <span class="inventory-name"><strong>${safe(name)}</strong><span>${safe(entry.deviceName || 'Unknown phone')} · ${safe(entry.bucketName || 'Unsorted')}</span></span>
        <span class="inventory-kind tag quiet">${safe(entryKind(entry))}</span>
        <span class="inventory-size mono">${fmtBytes(entry.size)}</span>
        <span class="inventory-date mono">${safe(fmtFullTime(entryTime(entry)))}</span>
      </button><button class="btn quiet sm icon-only inventory-reveal" data-act="reveal" tabindex="${index === activeIndex ? 0 : -1}" aria-label="Show ${safe(name)} in its folder" title="Show in folder">${icon('folder-open', { size: 17 })}</button>
    </div>`;
  }
  function paint() {
    if (disposed || !viewport.isConnected) return;
    updateFacets();
    const { start, end, top, height } = range();
    const filtered = Object.keys(EMPTY_QUERY).some((k) => k !== 'sort' && query[k] !== EMPTY_QUERY[k]);
    const count = !store.ready ? 'Loading…' : `${n(store.total)} ${filtered ? 'matching ' : ''}${grid ? (store.total === 1 ? 'file' : 'files') : (store.total === 1 ? 'transfer' : 'transfers')}`;
    if (count !== lastCount) { root.querySelector('#browser-count').textContent = count; lastCount = count; }
    const loading = !store.ready && !store.errors.size;
    viewport.setAttribute('aria-busy', String(loading));
    root.querySelector('#browser-index').max = String(Math.max(1, store.total));
    root.querySelector('#browser-reset').hidden = !filtered;
    const error = [...store.errors.values()][0];
    errorEl.hidden = !error;
    if (error) errorEl.querySelector('span').textContent = `Couldn't load files. ${error}`;
    emptyEl.hidden = !store.ready || store.total > 0;
    if (!emptyEl.hidden) {
      emptyEl.querySelector('h2').textContent = filtered ? 'No matching files' : grid ? 'Your contact sheet starts here' : 'No transfers yet';
      emptyEl.querySelector('p').textContent = filtered ? 'Try another name, phone, album or date. Search covers the whole collection.' : grid ? 'Send a backup from your phone. All saved photos and videos will appear here.' : 'Completed file transfers will appear here.';
    }
    viewport.style.height = `${height}px`;
    windowEl.style.transform = `translateY(${top}px)`;
    windowEl.style.setProperty('--media-columns', columns);
    windowEl.style.setProperty('--media-row-height', `${rowHeight}px`);
    const entries = [];
    for (let i = start; i < end; i++) entries.push(store.item(i));
    const signature = JSON.stringify([start, end, columns, rowHeight, entries.map((e) => e ? [entryKey(e), e.deviceName] : null)]);
    if (signature !== lastPaint) {
      const focused = windowEl.contains(document.activeElement) ? Number(document.activeElement.closest('[data-index]')?.dataset.index) : null;
      const previous = new Map([...windowEl.querySelectorAll('.frame[data-key], .inventory-row[data-key]')].map((el) => [el.dataset.key, el]));
      const scratch = document.createElement('div');
      let html = '';
      for (let i = 0; i < entries.length; i += columns) {
        html += `<div class="media-grid-row">${entries.slice(i, i + columns).map((entry, j) => rowHtml(entry, start + i + j)).join('')}</div>`;
      }
      scratch.innerHTML = html;
      reuseFrames(scratch, previous);
      reuseRowThumbnails(scratch, previous);
      detachThumbs(windowEl);
      windowEl.replaceChildren(...scratch.childNodes);
      windowEl.querySelectorAll('.media-cell .frame').forEach((el) => { el.tabIndex = Number(el.parentElement.dataset.index) === activeIndex ? 0 : -1; });
      attachThumbs(windowEl);
      lastPaint = signature;
      // A long keyboard jump owns the focus target. Do not replace it with the old,
      // now off-screen row while the destination page is still loading.
      if (pendingFocus === null && focused !== null && Number.isFinite(focused)) focusVisible(focused);
    }
    if (pendingFocus != null && focusVisible(pendingFocus)) pendingFocus = null;
    if (!store.ready) store.load(0); else if (end > start) store.request(start, end);
    if (!grid && store.meta.historyCap) {
      root.querySelector('#browser-foot').textContent = `History keeps the newest ${n(store.meta.historyCap)} transfer events. Every saved file remains available in Photos and in its phone folder.`;
    }
  }
  function focusVisible(index) {
    const target = windowEl.querySelector(`[data-index="${index}"] .frame, [data-index="${index}"] .inventory-entry`);
    if (!target) return false;
    activeIndex = index;
    windowEl.querySelectorAll('.frame, .inventory-entry, .inventory-reveal').forEach((el) => {
      el.tabIndex = Number(el.closest('[data-index]')?.dataset.index) === activeIndex ? 0 : -1;
    });
    target.focus({ preventScroll: true }); return true;
  }
  function focusItem(index, scroll = true) {
    if (!store.total) return;
    activeIndex = Math.max(0, Math.min(store.total - 1, index));
    pendingFocus = activeIndex;
    if (scroll) {
      dimensions();
      const y = scrollOffset() + Math.floor(activeIndex / columns) * rowHeight;
      if (y < main.scrollTop + 40 || y + rowHeight > main.scrollTop + main.clientHeight) main.scrollTo({ top: Math.max(0, y - 48), behavior: 'instant' });
    }
    if (focusVisible(activeIndex)) pendingFocus = null;
    schedule();
  }
  async function open(index, reveal = false) {
    const entry = store.item(index);
    if (!entry) return;
    try {
      const ok = await (reveal ? window.api.revealFile(fileArgs(entry)) : window.api.openFile(fileArgs(entry)));
      if (!ok) showToast('That file is no longer available in the Pherry folder.', 'error');
    } catch { showToast('Could not open that file. Try showing it in its folder.', 'error'); }
  }
  function reset(goTop = true) {
    clearTimeout(timer);
    if (query.dateFrom && query.dateTo && query.dateFrom > query.dateTo) {
      const error = root.querySelector('#query-error'); error.hidden = false; error.textContent = 'The end date must be on or after the start date.'; return;
    }
    root.querySelector('#query-error').hidden = true;
    Object.assign(preferences, query);
    lastPaint = ''; lastRevision = state.revision; activeIndex = 0; pendingFocus = null;
    root.querySelector('#browser-refresh').hidden = true;
    if (goTop) main.scrollTo({ top: 0, behavior: 'instant' });
    store.reset(query); store.load(0);
  }
  for (const key of Object.keys(EMPTY_QUERY)) {
    const control = root.querySelector('#' + filterId(key));
    control.addEventListener(key === 'query' ? 'input' : 'change', () => {
      query[key] = control.value;
      clearTimeout(timer);
      if (key === 'query') timer = setTimeout(() => reset(), 180); else reset();
    });
  }
  root.querySelector('#browser-reset').addEventListener('click', () => {
    Object.assign(query, EMPTY_QUERY);
    for (const key of Object.keys(EMPTY_QUERY)) root.querySelector('#' + filterId(key)).value = query[key];
    reset(); root.querySelector('#' + filterId('query')).focus();
  });
  root.querySelector('#browser-refresh').addEventListener('click', () => reset());
  root.querySelector('#browser-retry').addEventListener('click', () => store.retry());
  root.querySelector('#browser-jump').addEventListener('submit', (event) => {
    event.preventDefault(); focusItem(Number(root.querySelector('#browser-index').value) - 1);
  });
  root.querySelector('#browser-open')?.addEventListener('click', () => window.api.openFolder());
  root.querySelector('#history-export')?.addEventListener('click', exportHistory);
  root.querySelector('#history-clear')?.addEventListener('click', clearHistoryConfirm);
  windowEl.addEventListener('click', (event) => {
    const row = event.target.closest('[data-index]'); if (!row) return;
    activeIndex = Number(row.dataset.index); open(activeIndex, !!event.target.closest('[data-act="reveal"]'));
  });
  windowEl.addEventListener('contextmenu', (event) => {
    const row = event.target.closest('[data-index]'); if (!row) return;
    event.preventDefault(); open(Number(row.dataset.index), true);
  });
  windowEl.addEventListener('focusin', (event) => {
    const row = event.target.closest('[data-index]');
    if (row) activeIndex = Number(row.dataset.index);
  });
  windowEl.addEventListener('keydown', (event) => {
    if (event.altKey || event.ctrlKey || event.metaKey) return;
    const row = event.target.closest('[data-index]'); if (!row) return;
    const index = Number(row.dataset.index);
    const delta = { ArrowDown: columns, ArrowUp: -columns, ArrowRight: 1, ArrowLeft: -1,
      PageDown: columns * Math.max(1, Math.floor(main.clientHeight / rowHeight)), PageUp: -columns * Math.max(1, Math.floor(main.clientHeight / rowHeight)) }[event.key];
    if (delta != null) { event.preventDefault(); focusItem(index + delta); }
    else if (event.key === 'Home' || event.key === 'End') { event.preventDefault(); focusItem(event.key === 'Home' ? 0 : store.total - 1); }
    else if (event.key === 'F10' && event.shiftKey) { event.preventDefault(); open(index, true); }
  });
  main.addEventListener('scroll', schedule, { passive: true });
  const resize = new ResizeObserver(schedule); resize.observe(viewport);
  function canFollowArrivals() {
    return !disposed && pendingFocus === null && main.scrollTop < 20 &&
      !windowEl.contains(document.activeElement) && !root.querySelector('.query-controls').contains(document.activeElement);
  }
  reset(false);
  return {
    refresh() {
      // Explicit clear/import/delete/library changes replace the collection immediately. Only arrivals wait for the reader.
      if (lastEpoch !== state.collectionEpoch) { lastEpoch = state.collectionEpoch; reset(false); return; }
      if (state.revision === lastRevision) return;
      lastRevision = state.revision;
      const refreshButton = root.querySelector('#browser-refresh');
      refreshButton.hidden = false;
      if (canFollowArrivals()) {
        const revision = state.revision;
        store.refresh(canFollowArrivals).then((adopted) => {
          if (!disposed && adopted && revision === state.revision) refreshButton.hidden = true;
        });
      }
    },
    destroy() { disposed = true; clearTimeout(timer); cancelAnimationFrame(frame); resize.disconnect(); main.removeEventListener('scroll', schedule); detachThumbs(viewport); store.dispose(); },
  };
}
