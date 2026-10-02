const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const { LibraryStore } = require('../storage');
const { adoptLegacy, recoverLegacy } = require('../legacy');
const digest = (bytes, algorithm = 'sha256') => crypto.createHash(algorithm).update(bytes).digest('hex');

function fixture(t) {
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'pherry-legacy-test-'));
  const root = path.join(temporary, 'Photos'), database = path.join(temporary, 'state.sqlite');
  let store = new LibraryStore(database, root);
  t.after(() => {
    store.close();
    const checked = path.resolve(temporary);
    assert.ok(checked.startsWith(path.resolve(os.tmpdir()) + path.sep) && path.basename(checked).startsWith('pherry-legacy-test-'));
    fs.rmSync(checked, { recursive: true, force: true });
  });
  return {
    root, get store() { return store; },
    restart() { store.close(); store = new LibraryStore(database, root); },
    phone(id = 'phone-alpha') {
      const phone = store.pair(id, id);
      return { deviceId: id, deviceFolder: phone.folder };
    },
    legacy(bytes, relative = path.join('Camera', 'original.jpg')) {
      const file = path.join(root, relative);
      fs.mkdirSync(path.dirname(file), { recursive: true }); fs.writeFileSync(file, bytes);
      return store.saveMedia({ deviceId: 'legacy', fileName: path.basename(relative), bucketName: path.dirname(relative),
        relativePath: relative, size: bytes.length, time: 1000, hash: digest(bytes, 'md5'), hashAlgorithm: 'md5' });
    },
  };
}
const upload = (phone, bytes) => ({ ...phone, uploadId: crypto.randomUUID(), jobId: crypto.randomUUID(),
  fileName: 'original.jpg', bucketName: 'Camera', size: bytes.length, hash: digest(bytes), hashAlgorithm: 'sha256' });

test('legacy MD5 records move only after SHA-256 verification, preserving receipt identity and history links', async (t) => {
  const f = fixture(t), bytes = Buffer.from('verified original'), old = f.legacy(bytes), phone = f.phone();
  const result = await adoptLegacy(f.store, upload(phone, bytes));
  assert.equal(result.id, old.id); assert.equal(result.receiptId, old.receiptId);
  assert.equal(result.deviceId, phone.deviceId); assert.equal(result.hashAlgorithm, 'sha256');
  assert.equal(result.hash, digest(bytes)); assert.equal(result.adoptedLegacy, true); assert.equal(result.uploadId, undefined);
  assert.equal(fs.existsSync(path.join(f.root, old.relativePath)), false);
  assert.deepEqual(fs.readFileSync(path.join(f.root, result.relativePath)), bytes);
  assert.equal(f.store.totals().mediaCount, 1);
  const event = f.store.query('activity', {}, phone.deviceId).items[0];
  assert.equal(event.relativePath, result.relativePath); assert.equal(event.timestamp, 1000);
  assert.equal(f.store.query('media', { deviceId: 'legacy' }).totalCount, 0);
});

test('a mismatching legacy original is never moved even when name and size match', async (t) => {
  const f = fixture(t), old = f.legacy(Buffer.from('AAAA')), phone = f.phone();
  const result = await adoptLegacy(f.store, upload(phone, Buffer.from('BBBB')));
  assert.equal(result, null); assert.equal(fs.readFileSync(path.join(f.root, old.relativePath), 'utf8'), 'AAAA');
  assert.equal(f.store.file(old.id).deviceId, 'legacy');
});

test('two phones racing for one legacy original cannot share ownership or claim each other files', async (t) => {
  const f = fixture(t), bytes = Buffer.from('one original'); f.legacy(bytes);
  const first = f.phone('phone-first'), second = f.phone('phone-second');
  const results = await Promise.all([adoptLegacy(f.store, upload(first, bytes)), adoptLegacy(f.store, upload(second, bytes))]);
  assert.equal(results.filter(Boolean).length, 1);
  const winner = results.find(Boolean), other = winner.deviceId === first.deviceId ? second : first;
  assert.equal(await adoptLegacy(f.store, upload(other, bytes)), null);
  assert.deepEqual(fs.readFileSync(path.join(f.root, winner.relativePath)), bytes);
});

test('adoption retains existing phone files and chooses a free destination name', async (t) => {
  const f = fixture(t), bytes = Buffer.from('old original'); f.legacy(bytes); const phone = f.phone();
  const occupied = path.join(f.root, phone.deviceFolder, 'Camera', 'original.jpg');
  fs.mkdirSync(path.dirname(occupied), { recursive: true }); fs.writeFileSync(occupied, 'already here');
  const result = await adoptLegacy(f.store, upload(phone, bytes));
  assert.equal(result.fileName, 'original (1).jpg'); assert.equal(fs.readFileSync(occupied, 'utf8'), 'already here');
  assert.deepEqual(fs.readFileSync(path.join(f.root, result.relativePath)), bytes);
});

for (const renamed of [false, true]) test(`durable adoption intent recovers ${renamed ? 'after' : 'before'} rename`, async (t) => {
  const f = fixture(t), bytes = Buffer.from('recover this original'), old = f.legacy(bytes), phone = f.phone();
  await recoverLegacy(f.store); // Creates the helper-owned journal schema.
  const move = { mediaId: old.id, source: old.relativePath, destination: path.join(phone.deviceFolder, 'Camera', 'original.jpg'),
    ...phone, hash: digest(bytes), size: bytes.length, fileName: 'original.jpg', originalName: 'original.jpg', bucketName: 'Camera', startedAt: 1001 };
  f.store.q('INSERT INTO legacy_moves VALUES(?,?,?)').run(f.store.libraryId, old.id, JSON.stringify(move));
  if (renamed) {
    fs.mkdirSync(path.dirname(path.join(f.root, move.destination)), { recursive: true });
    fs.renameSync(path.join(f.root, move.source), path.join(f.root, move.destination));
  }
  f.restart();
  assert.deepEqual(await recoverLegacy(f.store), { recovered: 1, pending: 0, errors: [] });
  const saved = f.store.file(old.id);
  assert.equal(saved.deviceId, phone.deviceId); assert.equal(saved.adoptedLegacy, true);
  assert.deepEqual(fs.readFileSync(path.join(f.root, saved.relativePath)), bytes);
  assert.equal(f.store.q('SELECT COUNT(*) AS n FROM legacy_moves').get().n, 0);
  assert.deepEqual(await recoverLegacy(f.store), { recovered: 0, pending: 0, errors: [] });
});

test('an ownership change during verification cancels adoption before any rename', async (t) => {
  const f = fixture(t), bytes = Buffer.alloc(1024 * 1024, 7), old = f.legacy(bytes), phone = f.phone();
  const promised = adoptLegacy(f.store, upload(phone, bytes));
  f.store.q('UPDATE media SET device=? WHERE id=?').run('another-phone', old.id);
  await assert.rejects(promised, /changed ownership/);
  assert.equal(fs.existsSync(path.join(f.root, old.relativePath)), true);
  assert.equal(f.store.q('SELECT COUNT(*) AS n FROM legacy_moves').get().n, 0);
});

test('a live receipt failure after rename recovers the same original without a second upload', async (t) => {
  const f = fixture(t), bytes = Buffer.from('do not duplicate this original'), old = f.legacy(bytes), phone = f.phone();
  const originalTransaction = f.store.transaction;
  f.store.transaction = () => { throw new Error('database or disk is full'); };
  await assert.rejects(adoptLegacy(f.store, upload(phone, bytes)), { status: 409, code: 'LEGACY_REVIEW_REQUIRED' });
  const intent = JSON.parse(f.store.q('SELECT data FROM legacy_moves WHERE media=?').get(old.id).data);
  assert.equal(fs.existsSync(path.join(f.root, old.relativePath)), false);
  assert.deepEqual(fs.readFileSync(path.join(f.root, intent.destination)), bytes);
  assert.equal(f.store.file(old.id).deviceId, 'legacy');
  f.store.transaction = originalTransaction;
  const recovered = await adoptLegacy(f.store, upload(phone, bytes));
  assert.equal(recovered.id, old.id); assert.equal(recovered.relativePath, intent.destination);
  assert.equal(recovered.adoptedLegacy, true); assert.equal(f.store.totals().mediaCount, 1);
  assert.deepEqual(fs.readdirSync(path.dirname(path.join(f.root, recovered.relativePath))), ['original.jpg']);
  assert.equal(f.store.q('SELECT COUNT(*) AS n FROM legacy_moves').get().n, 0);
});

test('an unresolved live intent blocks retransmission and another phone cannot claim its reserved legacy row', async (t) => {
  const f = fixture(t), bytes = Buffer.from('keep this move pending'), old = f.legacy(bytes), phone = f.phone();
  const other = f.phone('another-phone'), originalTransaction = f.store.transaction;
  f.store.transaction = () => { throw new Error('database or disk is full'); };
  for (let attempt = 0; attempt < 3; attempt++) {
    await assert.rejects(adoptLegacy(f.store, upload(phone, bytes)), { status: 409, code: 'LEGACY_REVIEW_REQUIRED' });
  }
  assert.equal(f.store.q('SELECT COUNT(*) AS n FROM legacy_moves').get().n, 1);
  assert.equal(f.store.query('media', { deviceId: 'legacy' }).totalCount, 1);
  assert.equal(await adoptLegacy(f.store, upload(other, bytes)), null);
  assert.equal(f.store.file(old.id).deviceId, 'legacy');
  f.store.transaction = originalTransaction;
});

test('live recovery touches only the requested phone and content intent', async (t) => {
  const f = fixture(t), firstBytes = Buffer.from('first original'), secondBytes = Buffer.from('second original');
  const first = f.legacy(firstBytes), second = f.legacy(secondBytes, path.join('Camera', 'second.jpg')), phone = f.phone();
  const originalTransaction = f.store.transaction;
  f.store.transaction = () => { throw new Error('database or disk is full'); };
  await assert.rejects(adoptLegacy(f.store, upload(phone, firstBytes)), { code: 'LEGACY_REVIEW_REQUIRED' });
  await assert.rejects(adoptLegacy(f.store, upload(phone, secondBytes)), { code: 'LEGACY_REVIEW_REQUIRED' });
  f.store.transaction = originalTransaction;
  const recovered = await adoptLegacy(f.store, upload(phone, firstBytes));
  assert.equal(recovered.id, first.id);
  assert.equal(f.store.file(second.id).deviceId, 'legacy');
  assert.equal(f.store.q('SELECT COUNT(*) AS n FROM legacy_moves WHERE media=?').get(second.id).n, 1);
});

test('a changed moved file leaves its intent pending and never falls through to a new upload', async (t) => {
  const f = fixture(t), bytes = Buffer.from('AAAA'), old = f.legacy(bytes), phone = f.phone();
  const originalTransaction = f.store.transaction;
  f.store.transaction = () => { throw new Error('database or disk is full'); };
  await assert.rejects(adoptLegacy(f.store, upload(phone, bytes)), { code: 'LEGACY_REVIEW_REQUIRED' });
  f.store.transaction = originalTransaction;
  const intent = JSON.parse(f.store.q('SELECT data FROM legacy_moves WHERE media=?').get(old.id).data);
  fs.writeFileSync(path.join(f.root, intent.destination), 'BBBB');
  await assert.rejects(adoptLegacy(f.store, upload(phone, bytes)), { code: 'LEGACY_REVIEW_REQUIRED' });
  assert.equal(fs.readFileSync(path.join(f.root, intent.destination), 'utf8'), 'BBBB');
  assert.equal(f.store.file(old.id).deviceId, 'legacy');
});
