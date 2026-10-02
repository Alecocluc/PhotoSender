const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { component, fileComponent, inside, kind } = require('./storage');

const initialized = new WeakSet();
const busy = new WeakMap();

function initialize(store) {
  if (initialized.has(store)) return;
  store.db.exec(`CREATE TABLE IF NOT EXISTS legacy_moves (
    library TEXT NOT NULL, media INTEGER NOT NULL, data TEXT NOT NULL,
    PRIMARY KEY(library,media));
    CREATE INDEX IF NOT EXISTS media_legacy_size ON media(library,device,size);`);
  initialized.add(store);
}

async function sha256(file, onProgress) {
  const hash = crypto.createHash('sha256');
  for await (const bytes of fs.createReadStream(file)) {
    hash.update(bytes);
    onProgress?.(bytes.length);
  }
  return hash.digest('hex');
}
function unchanged(a, b) {
  return a.isFile() && b.isFile() && a.size === b.size && a.mtimeMs === b.mtimeMs &&
    a.ctimeMs === b.ctimeMs && a.ino === b.ino;
}
function currentLegacy(store, move) {
  const current = store.file(move.mediaId);
  if (!current || current.deviceId !== 'legacy' || current.relativePath !== move.source)
    throw new Error('The previous backup changed ownership during adoption. It was kept.');
  const phone = store.device(move.deviceId);
  if (!phone || phone.revoked || phone.folder !== move.deviceFolder)
    throw new Error('The phone no longer has access to this backup folder.');
  return current;
}
function destination(store, move) {
  const directory = inside(store.root, path.join(move.deviceFolder, move.bucketName));
  fs.mkdirSync(directory, { recursive: true });
  const extension = path.extname(move.fileName), stem = path.basename(move.fileName, extension);
  let name = move.fileName, number = 1;
  while (fs.existsSync(path.join(directory, name))) name = `${stem} (${number++})${extension}`;
  return path.relative(store.root, inside(store.root, path.relative(store.root, path.join(directory, name))));
}
function saveIntent(store, move) {
  store.q('INSERT INTO legacy_moves VALUES(?,?,?) ON CONFLICT(library,media) DO UPDATE SET data=excluded.data')
    .run(store.libraryId, move.mediaId, JSON.stringify(move));
}
function reviewRequired(cause) {
  return Object.assign(new Error('A previous backup move needs attention. Check space on the computer, then retry or restart Pherry before sending this file.'),
    { status: 409, code: 'LEGACY_REVIEW_REQUIRED', cause });
}
function syncDirectory(directory) {
  if (process.platform === 'win32') return;
  const handle = fs.openSync(directory, 'r');
  try { fs.fsyncSync(handle); } finally { fs.closeSync(handle); }
}

/** The move and the receipt update share a durable intent; ownership never changes before rename. */
function finish(store, move, before) {
  const target = inside(store.root, move.destination), stat = fs.statSync(target);
  const moved = {
    deviceId: move.deviceId, deviceFolder: move.deviceFolder,
    relativePath: move.destination, fileName: path.basename(move.destination),
    originalName: move.originalName, bucketName: move.bucketName, hash: move.hash,
    hashAlgorithm: 'sha256', size: stat.size, kind: kind(move.fileName),
    diskMtimeMs: stat.mtimeMs, diskCtimeMs: stat.ctimeMs,
    status: 'saved', integrityInvalid: false, adoptedLegacy: true, adoptedAt: move.startedAt,
  };
  // Adoption is an already-present original, not a new upload, including after response loss.
  const entry = { ...before, ...moved };
  delete entry.uploadId; delete entry.jobId;
  return store.transaction(() => {
    const result = store.q(`UPDATE media SET device=?,hash=?,algorithm=?,relative=?,name=?,album=?,kind=?,size=?,time=?,data=?
      WHERE library=? AND id=? AND device='legacy' AND relative=?`).run(
      entry.deviceId, entry.hash, entry.hashAlgorithm, entry.relativePath, entry.fileName,
      entry.bucketName, entry.kind, entry.size, entry.time, JSON.stringify(entry),
      store.libraryId, move.mediaId, move.source);
    if (result.changes !== 1) throw new Error('The previous backup changed during adoption.');
    // Existing history remains a history event, but its open/reveal action follows the moved file.
    store.q(`UPDATE activity SET device=?,name=?,album=?,kind=?,size=?,data=json_patch(data,?)
      WHERE library=? AND device='legacy' AND json_extract(data,'$.relativePath')=?`).run(
      moved.deviceId, moved.fileName, moved.bucketName, moved.kind, moved.size,
      JSON.stringify(moved), store.libraryId, move.source);
    store.q('DELETE FROM legacy_moves WHERE library=? AND media=?').run(store.libraryId, move.mediaId);
    return store.file(move.mediaId);
  });
}

function moveVerified(store, move, expected) {
  const original = currentLegacy(store, move);
  const source = inside(store.root, move.source);
  if (!unchanged(expected, fs.statSync(source))) throw new Error('The previous backup changed while it was being verified.');
  move.destination = destination(store, move);
  saveIntent(store, move);
  // No await between the ownership/path check, intent, rename and database commit.
  fs.renameSync(source, inside(store.root, move.destination));
  syncDirectory(path.dirname(source));
  const targetDirectory = path.dirname(inside(store.root, move.destination));
  for (let directory = targetDirectory; directory === store.root || directory.startsWith(store.root + path.sep); directory = path.dirname(directory)) {
    syncDirectory(directory);
    if (directory === store.root) break;
  }
  return finish(store, move, original);
}

async function recoverMove(store, move, onProgress) {
  const original = currentLegacy(store, move), source = inside(store.root, move.source);
  if (fs.existsSync(source)) {
    const stat = fs.statSync(source);
    if (stat.size !== move.size || await sha256(source, onProgress) !== move.hash)
      throw new Error('The previous backup changed before the move. It was kept.');
    return moveVerified(store, move, stat);
  }
  const target = inside(store.root, move.destination), stat = fs.statSync(target);
  if (stat.size !== move.size || await sha256(target, onProgress) !== move.hash || !unchanged(stat, fs.statSync(target)))
    throw new Error('The moved backup could not be verified.');
  currentLegacy(store, move);
  return finish(store, move, original);
}

/** Opt-in only: verify and move one unowned legacy original; never claim another phone's file. */
async function adoptLegacy(store, upload) {
  initialize(store);
  const phone = store.device(upload.deviceId);
  if (!phone || phone.revoked || upload.deviceId === 'legacy' || phone.folder !== upload.deviceFolder)
    throw new Error('A paired phone is required to adopt previous backups.');
  if (!/^[a-f0-9]{64}$/.test(upload.hash || '') || !Number.isSafeInteger(upload.size) || upload.size < 0)
    throw new Error('Adoption requires a file size and SHA-256.');
  let locked = busy.get(store);
  if (!locked) { locked = new Set(); busy.set(store, locked); }
  // A rename may have succeeded while its receipt transaction failed (for example, disk full).
  // Recover only this phone's matching intent, never interpret its missing old path as a new file.
  const pending = store.q(`SELECT media,data FROM legacy_moves WHERE library=?
    AND json_extract(data,'$.deviceId')=? AND json_extract(data,'$.hash')=? AND json_extract(data,'$.size')=? ORDER BY media`)
    .get(store.libraryId, upload.deviceId, upload.hash, upload.size);
  if (pending) {
    if (locked.has(pending.media)) throw reviewRequired(new Error('This previous backup move is still being recovered.'));
    locked.add(pending.media);
    try { return await recoverMove(store, JSON.parse(pending.data)); }
    catch (error) { throw reviewRequired(error); }
    finally { locked.delete(pending.media); }
  }
  const name = fileComponent(upload.fileName), album = component(upload.bucketName);
  const candidates = store.q(`SELECT id FROM media WHERE library=? AND device='legacy' AND size=?
    AND NOT EXISTS (SELECT 1 FROM legacy_moves WHERE legacy_moves.library=media.library AND legacy_moves.media=media.id)
    ORDER BY CASE WHEN name=? AND album=? THEN 0 WHEN name=? THEN 1 WHEN album=? THEN 2 ELSE 3 END,id`)
    .all(store.libraryId, upload.size, name, album, name, album);
  for (const candidate of candidates) {
    if (locked.has(candidate.id)) continue;
    locked.add(candidate.id);
    try {
      const original = store.file(candidate.id);
      if (!original || original.deviceId !== 'legacy') continue;
      const source = inside(store.root, original.relativePath);
      let stat, digest;
      try {
        stat = fs.statSync(source);
        if (!stat.isFile() || stat.size !== upload.size) continue;
        digest = await sha256(source);
        if (digest !== upload.hash || !unchanged(stat, fs.statSync(source))) continue;
      } catch { continue; }
      const move = {
        mediaId: candidate.id, source: original.relativePath, hash: upload.hash, size: upload.size,
        deviceId: upload.deviceId, deviceFolder: phone.folder, bucketName: album, fileName: name,
        originalName: String(upload.originalName || upload.fileName || name).slice(0, 200), startedAt: Date.now(),
      };
      try { return moveVerified(store, move, stat); }
      catch (error) {
        if (store.q('SELECT 1 FROM legacy_moves WHERE library=? AND media=?').get(store.libraryId, candidate.id))
          throw reviewRequired(error);
        throw error;
      }
    } finally { locked.delete(candidate.id); }
  }
  return null;
}

/** Run before listening. A persisted opt-in intent survives a crash before or after rename. */
async function recoverLegacy(store, onProgress) {
  initialize(store);
  const result = { recovered: 0, pending: 0, errors: [] };
  const intents = store.q('SELECT data FROM legacy_moves WHERE library=? ORDER BY media').all(store.libraryId);
  for (const row of intents) {
    const move = JSON.parse(row.data);
    try {
      await recoverMove(store, move, onProgress);
      result.recovered += 1;
    } catch (error) {
      result.pending += 1; result.errors.push({ mediaId: move.mediaId, error: error.message });
    }
  }
  return result;
}

module.exports = { adoptLegacy, recoverLegacy };
