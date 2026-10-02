import { state, activeJobs, jobState, computerName } from '../state.js';
import { escHtml, entryTime, entryKey, fmtBytes, fmtSpeed, fmtFullTime, primaryIP, sortedIPs, qrPayloadFor, n, plural } from '../utils.js';
import { register, navigate } from '../router.js';
import { renderQrCode } from '../qr.js';
import { attachThumbs, detachThumbs } from '../thumbs.js';
import { icon } from '../icons.js';
import { frameHtml, wireFrames, reuseFrames, emptyHtml } from '../components.js';
import { showToast } from '../shell.js';

let showPair = null, lastSignature = '', lastFiles = '', announced = '';
const SHOWN = 24;
const safe = escHtml;
const idOf = (job) => String(job.id || job.jobId || '');
const terminal = (job) => ['completed', 'failed', 'cancelled', 'canceled'].includes(jobState(job));
const labelFor = (status) => ({ planning: 'Checking this backup', waiting: 'Waiting for the phone', running: 'Receiving',
  receiving: 'Receiving', paused: 'Paused on the phone', completed: 'Backup complete', failed: 'Backup needs attention',
  cancelled: 'Backup cancelled', canceled: 'Backup cancelled' }[status] || status);
const jobLabel = (job) => jobState(job) === 'completed' && Number(job.failedFiles) > 0 ? 'Backup needs attention' : labelFor(jobState(job));

function model() {
  const jobs = activeJobs(), streams = (state.status?.receiving || []).filter((stream) =>
    !state.jobs.some((job) => idOf(job) === String(stream.jobId || '') && terminal(job)));
  const paired = state.devices.filter((device) => !device.revoked && !device.revokedAt);
  const ip = state.initializing ? '-' : primaryIP(state.ips);
  const port = state.server.port || state.settings?.port || 3210;
  return { jobs, streams, paired, ip, port, address: ip === '-' ? '' : `${ip}:${port}`,
    token: state.settings?.pairingToken || '', stopped: state.server.running === false,
    live: jobs.length > 0 || streams.length > 0,
    receipts: state.jobs.filter(terminal).sort((a, b) => (b.completedAt || b.updatedAt || 0) - (a.completedAt || a.updatedAt || 0)).slice(0, 3),
    files: (state.history.items?.length ? state.history.items : state.status?.recentActivity || []).slice(0, SHOWN) };
}
function headline(m) {
  if (state.initializing) return 'Starting the receiver';
  if (m.stopped) return 'Receiver stopped';
  if (!m.address) return 'Connect this computer to your network';
  if (m.jobs.length > 1) return `Backups from ${plural(m.jobs.length, 'phone')}`;
  if (m.jobs.length === 1) return `${labelFor(jobState(m.jobs[0]))} · ${m.jobs[0].deviceName || 'your phone'}`;
  if (m.streams.length) return `Receiving from ${m.streams[0].device || 'your phone'}`;
  return (state.status?.mediaCount > 0 || state.history.totalCount > 0 || state.jobs.length > 0)
    ? 'Ready for your next backup' : 'Ready for your first backup';
}
function ticketHtml(m) {
  const others = sortedIPs(state.ips).slice(1);
  return `<section class="ticket${m.live ? ' is-quiet' : ''}" aria-labelledby="ticket-title">
    <div class="ticket-body"><h2 class="ticket-title" id="ticket-title">Pair a phone</h2>
      <p>${m.address ? 'In Pherry on your phone, tap <strong>Pair a computer</strong> and scan this ticket.' : state.initializing ? 'Finding this computer’s network address…' : 'Connect this computer to Wi-Fi or Ethernet. Your phone needs to be on the same network.'}</p>
      ${m.address ? '<div class="qr-frame"><canvas id="pair-qr" role="img" aria-label="Pairing QR code"></canvas></div>' : `<div class="qr-frame is-empty">${state.initializing ? '' : icon('wifi-slash', { size: 32 })}<span>${state.initializing ? 'Starting…' : 'No network'}</span></div>`}
    </div><div class="ticket-tear" aria-hidden="true"><span class="perf"></span></div>
    <div class="ticket-stub">
      <div class="stub-field"><span class="label">Pairing code</span><span class="code" aria-label="Pairing code ${safe(m.token.split('').join(' '))}">${safe(m.token || '------')}</span></div>
      <div class="stub-field"><span class="label">Address</span><div class="addr-row"><span class="mono addr">${safe(m.address || (state.initializing ? 'Starting…' : 'Not on a network'))}</span>
        <button class="btn sm" id="copy-address" ${m.address ? '' : 'disabled'}>${icon('copy', { size: 15 })}<span>Copy</span></button></div>
        ${others.length ? `<details class="other-addrs"><summary>Other addresses</summary><ul>${others.map((ip) => `<li><button class="link mono" data-copy="${safe(ip + ':' + m.port)}">${safe(ip + ':' + m.port)}</button></li>`).join('')}</ul></details>` : ''}
      </div>
      <p class="stub-note">Pairing is required to send files. Each phone has its own folder and access. Manage paired phones in <button class="link" id="go-settings">Settings</button>.</p>
    </div></section>`;
}
function totals(job, streams) {
  const current = streams.filter((stream) => String(stream.jobId || '') === idOf(job));
  const completed = Number(job.completedFiles) || 0, skipped = Number(job.skippedFiles) || 0, failed = Number(job.failedFiles) || 0;
  const total = Number(job.totalFiles) || 0, bytes = Number(job.totalBytes) || 0;
  const received = Math.max(Number(job.receivedBytes) || 0, (Number(job.completedBytes) || 0) + current.reduce((sum, stream) => sum + (Number(stream.bytes) || 0), 0));
  const pct = bytes ? Math.min(100, Math.round(received / bytes * 100)) : total ? Math.min(100, Math.round((completed + skipped) / total * 100)) : 0;
  return { current, completed, skipped, failed, total, bytes, received, pct };
}
function jobHtml(job, m) {
  const f = totals(job, m.streams), status = jobState(job), name = job.deviceName || 'Your phone';
  const speed = fmtSpeed(Number(job.currentSpeedBytesPerSec) || (m.jobs.length === 1 ? state.status?.currentSpeedBytesPerSec : 0));
  // Total payload can include files already here. Only a real preflight can say how much disk is needed.
  const remaining = job.requiredBytes;
  const free = job.availableBytes ?? state.status?.freeBytes;
  const low = Number.isFinite(remaining) && Number.isFinite(free) && remaining > free;
  const current = f.current.length > 1 ? `${plural(f.current.length, 'file')} in progress` : f.current[0]?.fileName;
  return `<section class="job receiver-job" data-job-id="${safe(idOf(job))}" aria-label="Backup from ${safe(name)}">
    <div class="job-count"><span class="num">${n(f.completed)}</span><span class="unit"> of ${n(f.total)} saved</span></div>
    <div class="job-top"><span class="label">${safe(labelFor(status))}</span><span class="mono">${safe(name)}</span></div>
    <dl class="job-facts"><div><dt>Received</dt><dd class="mono">${fmtBytes(f.received)} of ${fmtBytes(f.bytes)}</dd></div>
      <div><dt>Speed</dt><dd class="mono">${status === 'paused' || status === 'waiting' ? '—' : speed.v + ' ' + speed.u}</dd></div>
      <div><dt>Already here</dt><dd class="mono">${n(f.skipped)}</dd></div>
      ${current ? `<div class="wide"><dt>Now</dt><dd class="mono" title="${safe(current)}">${safe(current)}</dd></div>` : ''}
    </dl>
    <div class="job-progress"><div class="job-bar" role="progressbar" aria-label="Backup from ${safe(name)}" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${f.pct}"><span class="fill" style="transform:scaleX(${f.pct / 100})"></span></div>
      <span class="job-progress-text">${status === 'waiting' ? 'Waiting for the phone to reconnect. Finished files are kept.' : status === 'paused' ? 'Resume this backup on your phone.' : f.total ? `${n(f.completed + f.skipped)} of ${n(f.total)} files accounted for` : 'Preparing the file list on your phone…'}</span></div>
    ${low ? `<p class="job-warning" role="alert">This backup needs ${fmtBytes(remaining)} more; this computer has ${fmtBytes(free)} free. Make room before continuing.</p>` : ''}
    ${f.failed || job.error ? `<p class="job-warning">${f.failed ? plural(f.failed, 'file') + ' need another try. ' : ''}${safe(job.error || '')}</p>` : ''}
  </section>`;
}
function receiptHtml(job) {
  const status = jobState(job), done = Number(job.completedFiles) || 0, skipped = Number(job.skippedFiles) || 0, failed = Number(job.failedFiles) || 0;
  const finished = status === 'completed' && failed === 0;
  return `<details class="receipt${failed || status === 'failed' ? ' needs-attention' : ''}" data-receipt="${safe(idOf(job))}">
    <summary><span class="receipt-icon">${icon(finished ? 'check' : 'warning-circle', { size: 18 })}</span>
      <span><strong>${safe(finished ? 'Backup complete' : jobLabel(job))}</strong><span>${safe(job.deviceName || 'Your phone')} · ${n(done)} saved · ${n(skipped)} already here${failed ? ` · ${n(failed)} failed` : ''}</span></span>
      ${icon('caret-down', { size: 15 })}</summary>
    <div class="receipt-body"><dl>
      <div><dt>Started</dt><dd>${safe(fmtFullTime(job.startedAt))}</dd></div>
      <div><dt>Finished</dt><dd>${safe(fmtFullTime(job.completedAt || job.updatedAt))}</dd></div>
      <div><dt>Saved here</dt><dd>${plural(done, 'file')} · ${fmtBytes(job.completedBytes || 0)}</dd></div>
      <div><dt>Already here</dt><dd>${plural(skipped, 'file')}</dd></div>
      <div><dt>Needs attention</dt><dd>${plural(failed, 'file')}</dd></div>
      ${job.deviceFolder ? `<div><dt>Phone folder</dt><dd class="mono">${safe(job.deviceFolder)}</dd></div>` : ''}
    </dl>${job.error ? `<p class="set-error">${safe(job.error)}</p>` : ''}
    <button class="btn sm" data-copy-receipt="${safe(idOf(job))}">${icon('copy', { size: 15 })}Copy receipt</button></div></details>`;
}
function jobsHtml(m) {
  const cards = m.jobs.map((job) => jobHtml(job, m)).join('');
  // Older senders may have a stream without a job. Show the actual file, without claiming completion.
  const untracked = m.streams.filter((stream) => !m.jobs.some((job) => idOf(job) === String(stream.jobId || '')));
  const streams = untracked.map((stream) => `<div class="stream-note">${icon('upload-simple', { size: 18 })}<span>Receiving <strong>${safe(stream.fileName || 'a file')}</strong> from ${safe(stream.device || 'your phone')}<span class="mono">${fmtBytes(stream.bytes)} of ${fmtBytes(stream.total)}</span></span></div>`).join('');
  return cards + streams;
}
function render() {
  const m = model();
  const expanded = showPair ?? (!m.paired.length && !m.live);
  const signature = JSON.stringify([state.initializing, m.address, m.token, m.stopped, state.server.error, sortedIPs(state.ips), expanded]);
  const root = document.querySelector('#view-root');
  let carriedFrames = [];
  if (!root.querySelector('#receiver-title') || signature !== lastSignature) {
    const focus = root.contains(document.activeElement) ? document.activeElement.id : '';
    carriedFrames = [...root.querySelectorAll('#sheet-slot .frame[data-key]')];
    detachThumbs(root);
    root.innerHTML = `
      <header class="view-head"><div><h1 class="view-title" id="receiver-title">${safe(headline(m))}</h1>
        <div class="view-sub">${safe(computerName())}${m.address ? ' · ' + safe(m.address) : ''}</div></div>
        <div class="view-actions"><button class="btn quiet" id="pair-toggle" aria-expanded="${expanded}" aria-controls="pairing-panel">${icon('qr-code', { size: 17 })}${expanded ? 'Hide pairing ticket' : 'Pair another phone'}</button>
        <button class="btn" id="open-folder">${icon('folder-open', { size: 17 })}Open folder</button></div></header>
      ${m.stopped ? `<div class="notice" role="alert">${icon('warning-circle', { size: 20 })}<div class="notice-text">Pherry isn't receiving.<span>${safe(state.server.error || 'Open Settings to check the receiver.')}</span></div><button class="btn danger sm" id="fix-port">Settings</button></div>` : ''}
      <div class="receiver${expanded ? '' : ' ticket-collapsed'}">
        <div id="pairing-panel"${expanded ? '' : ' hidden'}>${ticketHtml(m)}</div>
        <div class="arrivals">
          <div class="visually-hidden" id="job-status" role="status" aria-live="polite"></div>
          <div class="receiver-jobs" id="job-slot"></div>
          <div class="receiver-receipts" id="receipt-slot"></div>
          <div class="capacity-note" id="capacity-note"></div>
          <div class="arrivals-head"><div><h2 class="section-title">Latest arrivals</h2><div class="tally mono" id="arrivals-tally"></div></div><button class="btn quiet sm" id="see-photos">All photos${icon('arrow-right', { size: 15 })}</button></div>
          <div id="sheet-slot"></div>
        </div>
      </div>`;
    root.querySelector('#pair-toggle').addEventListener('click', () => { showPair = !expanded; render(); });
    root.querySelector('#open-folder').addEventListener('click', () => window.api.openFolder());
    root.querySelector('#see-photos').addEventListener('click', () => navigate('photos'));
    root.querySelector('#go-settings').addEventListener('click', () => navigate('settings'));
    root.querySelector('#fix-port')?.addEventListener('click', () => navigate('settings'));
    root.querySelector('#copy-address').addEventListener('click', () => copy(m.address));
    root.querySelectorAll('[data-copy]').forEach((button) => button.addEventListener('click', () => copy(button.dataset.copy)));
    if (m.address) {
      try { renderQrCode(root.querySelector('#pair-qr'), qrPayloadFor(m.ip, m.port, m.token)); }
      catch { root.querySelector('.qr-frame').textContent = 'Use the address and pairing code below.'; }
    }
    root.querySelector('#receipt-slot').addEventListener('click', async (event) => {
      const button = event.target.closest('[data-copy-receipt]'); if (!button) return;
      const job = state.jobs.find((item) => idOf(item) === button.dataset.copyReceipt); if (!job) return;
      const lines = ['Pherry backup receipt', job.deviceName || 'Your phone', jobLabel(job),
        `Saved: ${job.completedFiles || 0} files (${fmtBytes(job.completedBytes || 0)})`,
        `Already here: ${job.skippedFiles || 0}`, `Failed: ${job.failedFiles || 0}`,
        `Finished: ${fmtFullTime(job.completedAt || job.updatedAt)}`, `Backup: ${idOf(job)}`];
      await copy(lines.join('\n'), 'Backup receipt copied.');
    });
    lastSignature = signature; lastFiles = '';
    if (focus) document.getElementById(focus)?.focus({ preventScroll: true });
  }
  root.querySelector('.ticket')?.classList.toggle('is-quiet', m.live);
  root.querySelector('#receiver-title').textContent = headline(m);
  const jobSlot = root.querySelector('#job-slot'), jobMarkup = jobsHtml(m);
  if (jobSlot.dataset.markup !== jobMarkup) {
    const known = new Set([...jobSlot.querySelectorAll('[data-job-id]')].map((el) => el.dataset.jobId));
    const scratch = document.createElement('div'); scratch.innerHTML = jobMarkup;
    // A status tick changes numbers. The entrance belongs only to the start of a new job.
    scratch.querySelectorAll('[data-job-id]').forEach((el) => { if (known.has(el.dataset.jobId)) el.style.animation = 'none'; });
    jobSlot.replaceChildren(...scratch.childNodes); jobSlot.dataset.markup = jobMarkup;
  }
  const receiptSlot = root.querySelector('#receipt-slot');
  const receiptMarkup = m.receipts.map(receiptHtml).join('');
  if (receiptSlot.dataset.markup !== receiptMarkup) {
    const opened = new Set([...receiptSlot.querySelectorAll('details[open]')].map((el) => el.dataset.receipt));
    const focused = document.activeElement?.dataset?.copyReceipt;
    receiptSlot.innerHTML = receiptMarkup; receiptSlot.dataset.markup = receiptMarkup;
    receiptSlot.querySelectorAll('details').forEach((el) => { el.open = opened.has(el.dataset.receipt); });
    if (focused) [...receiptSlot.querySelectorAll('[data-copy-receipt]')].find((el) => el.dataset.copyReceipt === focused)?.focus({ preventScroll: true });
  }
  const summary = JSON.stringify(state.jobs.map((job) => [idOf(job), jobState(job)]));
  if (summary !== announced) { root.querySelector('#job-status').textContent = [...m.jobs, ...m.receipts.slice(0, 1)].map((job) => `${job.deviceName || 'Phone'}: ${jobLabel(job)}`).join('. '); announced = summary; }
  const capacity = root.querySelector('#capacity-note'), free = state.status?.freeBytes;
  capacity.textContent = Number.isFinite(free) ? `${fmtBytes(free)} free on this computer · Each phone keeps its own copy` : 'Each phone keeps its own copy in the Pherry folder';
  root.querySelector('#arrivals-tally').textContent = `${plural(Number(state.status?.mediaCount || 0), 'file')} saved in your library`;
  const keys = JSON.stringify(m.files.map(entryKey));
  if (keys !== lastFiles) {
    const slot = root.querySelector('#sheet-slot'), active = document.activeElement;
    const focusKey = slot.contains(active) ? active.closest('.frame')?.dataset.key : null;
    const previous = new Map([...carriedFrames, ...slot.querySelectorAll('.frame[data-key]')].map((el) => [el.dataset.key, el]));
    const scratch = document.createElement('div');
    scratch.innerHTML = m.files.length ? `<div class="sheet" id="arrivals-sheet">${m.files.map((entry, index) => frameHtml(entry, { number: index + 1, edgeRight: fmtStamp(entryTime(entry)), edgeBottom: [entry.deviceName, entry.bucketName].filter(Boolean).join(' / ') })).join('')}</div>` :
      emptyHtml({ title: state.initializing ? 'Opening your contact sheet…' : 'Nothing has arrived yet', body: 'Start a backup on your phone. Finished photos and videos will land here.' });
    wireFrames(scratch); reuseFrames(scratch, previous); detachThumbs(slot); slot.replaceChildren(...scratch.childNodes); attachThumbs(slot);
    if (focusKey) [...slot.querySelectorAll('.frame')].find((el) => el.dataset.key === focusKey)?.focus({ preventScroll: true });
    lastFiles = keys;
  }
}
function fmtStamp(ts) { return ts ? new Date(ts).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' }) : ''; }
async function copy(text, message = 'Address copied.') {
  try { await navigator.clipboard.writeText(text); showToast(message); }
  catch { showToast('Could not copy. Select the text and copy it by hand.', 'error'); }
}
register('receiver', render, () => detachThumbs(document.querySelector('#view-root')));
export { render as renderReceiver };
