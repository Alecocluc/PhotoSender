const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const crypto = require("node:crypto");
const { createServer } = require("../receiver");
const hash = (b) => crypto.createHash("sha256").update(b).digest("hex");
const id = () => crypto.randomUUID();
async function fixture(t) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "pherry-test-"));
  const root = path.join(dir, "Photos"),
    databasePath = path.join(dir, "state.sqlite");
  const options = {
    databasePath,
    pairingToken: "abcdef",
    adminToken: "desktop-secret",
    deviceId: "desktop-identity",
  };
  let app, server;
  const start = async () => {
    app = createServer(root, options);
    await app.locals.ready;
    server = await new Promise((r) => {
      const s = app.listen(0, "127.0.0.1", () => r(s));
    });
  };
  const stop = async () => {
    server.closeAllConnections();
    await new Promise((r) => server.close(r));
    app.locals.close();
  };
  await start();
  t.after(async () => {
    await stop();
    const checked = path.resolve(dir);
    assert.ok(
      checked.startsWith(path.resolve(os.tmpdir()) + path.sep) &&
        path.basename(checked).startsWith("pherry-test-"),
    );
    fs.rmSync(checked, { recursive: true, force: true });
  });
  async function request(
    route,
    { method = "GET", body, token, admin = false, headers = {} } = {},
  ) {
    const h = {
      ...headers,
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(admin ? { "X-Pherry-Admin": "desktop-secret" } : {}),
    };
    if (body && !Buffer.isBuffer(body)) h["Content-Type"] = "application/json";
    const res = await fetch(
      `http://127.0.0.1:${server.address().port}${route}`,
      {
        method,
        headers: h,
        body: body
          ? Buffer.isBuffer(body)
            ? body
            : JSON.stringify(body)
          : undefined,
      },
    );
    return { ...(await res.json()), status: res.status };
  }
  const pair = async (clientId = id(), name = "Pixel") => ({
    ...(await request("/pair", {
      method: "POST",
      body: { clientId, deviceName: name, pairingCode: "abcdef" },
    })),
    clientId,
  });
  const job = async (p) => {
    const jobId = id();
    assert.equal(
      (
        await request(`/v2/jobs/${jobId}`, {
          method: "PUT",
          token: p.credential,
          body: { state: "running", totalFiles: 1, totalBytes: 10 },
        })
      ).status,
      200,
    );
    return jobId;
  };
  const begin = async (p, bytes, jobId) =>
    request("/v2/uploads", {
      method: "POST",
      token: p.credential,
      body: {
        uploadId: id(),
        jobId: jobId || (await job(p)),
        hash: hash(bytes),
        hashAlgorithm: "sha256",
        size: bytes.length,
        fileName: "photo.jpg",
        bucketName: "Camera",
      },
    });
  const chunk = async (p, u, bytes, offset = 0) =>
    request(`/v2/uploads/${u.uploadId}`, {
      method: "PATCH",
      token: p.credential,
      headers: {
        "Content-Type": "application/octet-stream",
        "Upload-Offset": String(offset),
      },
      body: bytes,
    });
  const complete = async (p, u) =>
    request(`/v2/uploads/${u.uploadId}/complete`, {
      method: "POST",
      token: p.credential,
      body: {},
    });
  return {
    dir,
    root,
    request,
    pair,
    job,
    begin,
    chunk,
    complete,
    get store() {
      return app.locals.store;
    },
    restart: async () => {
      await stop();
      await start();
    },
  };
}
test("pairing is required; phones cannot inspect or delete another phone backup", async (t) => {
  const f = await fixture(t);
  assert.equal((await f.request("/history")).status, 401);
  assert.equal(
    (await f.request("/status", { headers: { Origin: "https://example.com" } }))
      .status,
    403,
  );
  const a = await f.pair(),
    b = await f.pair(),
    bytes = Buffer.from("photo-one");
  assert.equal(
    (
      await f.request("/v2/preflight", {
        method: "POST",
        token: a.credential,
        headers: { "X-Pherry-Library": "a-different-library" },
        body: { totalBytes: 0 },
      })
    ).status,
    409,
  );
  const u = await f.begin(a, bytes);
  assert.equal(u.status, 201);
  assert.equal((await f.chunk(a, u, bytes)).offset, bytes.length);
  const saved = await f.complete(a, u);
  assert.equal(saved.status, 200);
  assert.equal(
    (await f.request("/v2/media", { token: b.credential })).totalCount,
    0,
  );
  const deleted = await f.request("/v2/sync/delete", {
    method: "POST",
    token: b.credential,
    body: { entries: [{ hash: hash(bytes), receiptId: saved.receiptId }] },
  });
  assert.equal(deleted.deleted, 0);
  assert.ok(fs.existsSync(path.join(f.root, saved.relativePath)));
  assert.equal(
    (await f.request(`/v2/uploads/${u.uploadId}`, { token: b.credential }))
      .status,
    404,
  );
});
test("the same content on two phones has independent folders and receipts", async (t) => {
  const f = await fixture(t),
    a = await f.pair(),
    b = await f.pair(),
    bytes = Buffer.from("family-photo");
  const receipts = [];
  for (const p of [a, b]) {
    const u = await f.begin(p, bytes);
    await f.chunk(p, u, bytes);
    receipts.push(await f.complete(p, u));
  }
  assert.notEqual(receipts[0].relativePath, receipts[1].relativePath);
  assert.notEqual(a.deviceFolder, b.deviceFolder);
  assert.equal((await f.request("/v2/media", { admin: true })).totalCount, 2);
});
test("acknowledged chunks survive restart and wrong offsets are rejected", async (t) => {
  const f = await fixture(t),
    p = await f.pair(),
    bytes = Buffer.from("abcdefghijk");
  const u = await f.begin(p, bytes);
  assert.equal((await f.chunk(p, u, bytes.subarray(0, 4))).offset, 4);
  await f.restart();
  assert.equal(
    (await f.request(`/v2/uploads/${u.uploadId}`, { token: p.credential }))
      .offset,
    4,
  );
  assert.equal((await f.chunk(p, u, bytes.subarray(4), 0)).status, 409);
  assert.equal(
    (await f.chunk(p, u, bytes.subarray(4), 4)).offset,
    bytes.length,
  );
  const done = await f.complete(p, u);
  assert.equal(done.status, 200);
  assert.deepEqual(
    fs.readFileSync(path.join(f.root, done.relativePath)),
    bytes,
  );
});
test("checksum rejection restarts only that file and concurrent duplicates share a receipt", async (t) => {
  const f = await fixture(t),
    p = await f.pair(),
    bytes = Buffer.from("correct");
  const bad = await f.begin(p, bytes);
  await f.chunk(p, bad, Buffer.from("corrupt"));
  assert.equal((await f.complete(p, bad)).status, 422);
  assert.equal(
    (await f.request(`/v2/uploads/${bad.uploadId}`, { token: p.credential }))
      .offset,
    0,
  );
  const a = await f.begin(p, bytes),
    b = await f.begin(p, bytes);
  await Promise.all([f.chunk(p, a, bytes), f.chunk(p, b, bytes)]);
  const results = await Promise.all([f.complete(p, a), f.complete(p, b)]);
  assert.equal(results[0].receiptId, results[1].receiptId);
  assert.equal((await f.request("/v2/media", { admin: true })).totalCount, 1);
});
test("failed deletion retains its receipt; checksum audit detects same-size corruption", async (t) => {
  const f = await fixture(t),
    p = await f.pair(),
    bytes = Buffer.from("original");
  const u = await f.begin(p, bytes);
  await f.chunk(p, u, bytes);
  const saved = await f.complete(p, u);
  const unlink = fs.promises.unlink;
  fs.promises.unlink = async (file) => {
    if (file.endsWith("photo.jpg"))
      throw Object.assign(new Error("busy"), { code: "EPERM" });
    return unlink(file);
  };
  let result;
  try {
    result = await f.request("/v2/sync/delete", {
      method: "POST",
      token: p.credential,
      body: { entries: [{ hash: hash(bytes) }] },
    });
  } finally {
    fs.promises.unlink = unlink;
  }
  assert.equal(result.deleted, 0);
  assert.equal(result.results[0].error, "EPERM");
  assert.ok(f.store.file(saved.receiptId));
  fs.writeFileSync(path.join(f.root, saved.relativePath), "changed!");
  // Model latent corruption in an older receipt with no reliable stat fingerprint.
  f.store
    .q(
      "UPDATE media SET data=json_remove(data,'$.diskMtimeMs','$.diskCtimeMs') WHERE id=?",
    )
    .run(Number(saved.receiptId));
  const audit = await f.request("/v2/files/exists", {
    method: "POST",
    token: p.credential,
    body: { hashes: [hash(bytes)], verify: true },
  });
  assert.equal(audit.files[0].exists, false);
  assert.equal(f.store.file(saved.receiptId).integrityInvalid, 1);
  const repair = await f.begin(p, bytes);
  assert.equal(repair.status, 201);
  assert.equal(repair.complete, false);
  assert.equal(
    fs.readFileSync(path.join(f.root, saved.relativePath), "utf8"),
    "changed!",
  );
});
test("inventory, server-side filters, and stable snapshots exceed the activity cap", async (t) => {
  const f = await fixture(t),
    p = await f.pair();
  // Seed 20k independent inventory rows in one transaction; no fake disk files or network speed claims.
  f.store.transaction(() => {
    const q = f.store.q(
      "INSERT INTO media(library,device,hash,algorithm,relative,name,album,kind,size,time,data) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
    );
    for (let i = 0; i < 20000; i++)
      q.run(
        f.store.libraryId,
        p.clientId,
        String(i),
        "sha256",
        `Camera/${i}.jpg`,
        `${i}.jpg`,
        i === 0 ? "Old album" : "Camera",
        "photo",
        5,
        i,
        JSON.stringify({
          fileName: `${i}.jpg`,
          bucketName: i === 0 ? "Old album" : "Camera",
          relativePath: `Camera/${i}.jpg`,
          size: 5,
        }),
      );
  });
  const first = await f.request("/v2/media?limit=120", { admin: true });
  assert.equal(first.totalCount, 20000);
  assert.equal(first.items.length, 120);
  const filtered = await f.request("/v2/media?album=Old%20album", {
    admin: true,
  });
  assert.equal(filtered.totalCount, 1);
  assert.equal(filtered.items[0].fileName, "0.jpg");
  await f.request("/history/clear", { method: "POST", admin: true, body: {} });
  assert.equal(
    (await f.request("/v2/media", { admin: true })).totalCount,
    20000,
  );
  assert.equal(
    (
      await f.request(
        `/v2/media?limit=120&offset=120&snapshot=${first.snapshot}`,
        { admin: true },
      )
    ).items[0].fileName,
    "19879.jpg",
  );
});

test("enrollment codes cannot impersonate an existing phone; revocation preserves ownership proof", async (t) => {
  const f = await fixture(t),
    p = await f.pair();
  const stolen = await f.pair(p.clientId, "Impostor");
  assert.equal(stolen.status, 409);
  assert.equal(
    (await f.request("/v2/identity", { token: p.credential })).status,
    200,
  );
  await f.request(`/v2/devices/${p.clientId}`, {
    method: "DELETE",
    admin: true,
  });
  assert.equal(
    (await f.request("/v2/identity", { token: p.credential })).status,
    401,
  );
  const paired = await f.request("/pair", {
    method: "POST",
    body: {
      clientId: p.clientId,
      deviceName: "Pixel",
      pairingCode: "abcdef",
      credential: p.credential,
    },
  });
  assert.equal(paired.status, 200);
  assert.equal(paired.deviceFolder, p.deviceFolder);
  await f.request("/v2/pairing-code", {
    method: "POST",
    admin: true,
    body: { code: "fedcba" },
  });
  assert.equal((await f.pair()).status, 401);
  assert.equal(
    (await f.request("/v2/identity", { token: paired.credential })).status,
    200,
  );
});

test("removed or modified completed copies cannot produce a stale success receipt", async (t) => {
  const f = await fixture(t),
    p = await f.pair(),
    bytes = Buffer.from("original");
  const u = await f.begin(p, bytes);
  await f.chunk(p, u, bytes);
  const saved = await f.complete(p, u);
  fs.writeFileSync(path.join(f.root, saved.relativePath), "changed!");
  assert.equal((await f.complete(p, u)).status, 410);
  assert.equal(
    (await f.request(`/v2/uploads/${u.uploadId}`, { token: p.credential }))
      .status,
    410,
  );
  const restarted = await f.request("/v2/uploads", {
    method: "POST",
    token: p.credential,
    body: {
      uploadId: u.uploadId,
      jobId: u.jobId,
      hash: hash(bytes),
      hashAlgorithm: "sha256",
      size: bytes.length,
      fileName: "photo.jpg",
      bucketName: "Camera",
    },
  });
  assert.equal(restarted.status, 201);
  assert.equal(restarted.offset, 0);
  await f.chunk(p, restarted, bytes);
  const fresh = await f.complete(p, restarted);
  assert.equal(fresh.status, 200);
  assert.notEqual(fresh.relativePath, saved.relativePath);
  const job = await f.request(`/v2/jobs/${u.jobId}`, { token: p.credential });
  assert.equal(job.savedFiles, 1);
  assert.equal(job.savedBytes, bytes.length);
  assert.equal(
    fs.readFileSync(path.join(f.root, saved.relativePath), "utf8"),
    "changed!",
  );
});

test("cleanup and mirror deletion keep computer copies whose contents changed", async (t) => {
  const f = await fixture(t),
    p = await f.pair(),
    bytes = Buffer.from("AAAA");
  const u = await f.begin(p, bytes);
  await f.chunk(p, u, bytes);
  const saved = await f.complete(p, u);
  const second = path.join(p.deviceFolder, "Camera", "second.jpg");
  fs.writeFileSync(path.join(f.root, second), "BBBB");
  // A stale imported index can claim the same hash without a filesystem fingerprint.
  f.store.saveMedia({
    ...saved,
    relativePath: second,
    fileName: "second.jpg",
    diskMtimeMs: undefined,
    diskCtimeMs: undefined,
  });
  const clean = await f.request("/history/remove-duplicates", {
    method: "POST",
    admin: true,
    body: {},
  });
  assert.equal(clean.deleted, 0);
  const mirror = await f.request("/v2/sync/delete", {
    method: "POST",
    token: p.credential,
    body: {
      entries: [
        {
          hash: hash(bytes),
          receiptId: f.store
            .find(p.clientId, hash(bytes))
            .find((e) => e.relativePath === second).receiptId,
        },
      ],
    },
  });
  assert.equal(mirror.success, false);
  assert.equal(mirror.deleted, 0);
  assert.equal(fs.readFileSync(path.join(f.root, second), "utf8"), "BBBB");
});

test("duplicate index aliases cannot make cleanup remove the only physical copy", async (t) => {
  const f = await fixture(t),
    p = await f.pair(),
    bytes = Buffer.from("only-copy");
  const u = await f.begin(p, bytes);
  await f.chunk(p, u, bytes);
  const saved = await f.complete(p, u);
  f.store.saveMedia({
    ...saved,
    relativePath:
      path.dirname(saved.relativePath) +
      path.sep +
      "." +
      path.sep +
      saved.fileName,
  });
  const cleaned = await f.request("/history/remove-duplicates", {
    method: "POST",
    admin: true,
    body: {},
  });
  assert.equal(cleaned.deleted, 0);
  assert.equal(
    fs.readFileSync(path.join(f.root, saved.relativePath), "utf8"),
    "only-copy",
  );
});

test("rename intent recovers a committed file after receiver interruption", async (t) => {
  const f = await fixture(t),
    p = await f.pair(),
    bytes = Buffer.from("recoverable");
  const u = await f.begin(p, bytes);
  await f.chunk(p, u, bytes);
  const intent = f.store.upload(u.uploadId);
  intent.finalPath = path.join(p.deviceFolder, "Camera", "photo.jpg");
  f.store.saveUpload(intent);
  fs.mkdirSync(path.dirname(path.join(f.root, intent.finalPath)), {
    recursive: true,
  });
  fs.renameSync(
    path.join(f.root, ".pherry", "uploads", `${u.uploadId}.part`),
    path.join(f.root, intent.finalPath),
  );
  await f.restart();
  const receipt = await f.complete(p, u);
  assert.equal(receipt.status, 200);
  assert.equal(receipt.complete, true);
  assert.equal(
    (await f.request("/v2/media", { token: p.credential })).totalCount,
    1,
  );
});

test("index rebuild updates ownership, fields and local calendar filtering", async (t) => {
  const f = await fixture(t),
    p = await f.pair();
  const base = {
    deviceId: "legacy",
    relativePath: "test.jpg",
    fileName: "old.jpg",
    bucketName: "Old",
    size: 1,
    hash: "abc",
    time: 1,
  };
  f.store.saveMedia(base, { arrival: false });
  f.store.saveMedia(
    {
      ...base,
      deviceId: p.clientId,
      fileName: "new.jpg",
      bucketName: "New",
      time: new Date(2026, 9, 2, 0, 15).getTime(),
    },
    { arrival: false },
  );
  const result = f.store.query(
    "media",
    { dateFrom: "2026-10-02", dateTo: "2026-10-02", album: "New" },
    p.clientId,
  );
  assert.equal(result.totalCount, 1);
  assert.equal(result.items[0].deviceId, p.clientId);
  assert.equal(result.items[0].fileName, "new.jpg");
  assert.equal(
    f.store.query("media", { dateFrom: "2026-10-03" }, p.clientId).totalCount,
    0,
  );
});

test("large inventory exports can be imported through the authenticated desktop route", async (t) => {
  const f = await fixture(t);
  fs.mkdirSync(path.join(f.root, "Camera"));
  fs.writeFileSync(path.join(f.root, "Camera", "photo.jpg"), "x");
  const result = await f.request("/history/import", {
    method: "POST",
    admin: true,
    body: {
      completedFiles: [
        {
          fileName: "photo.jpg",
          bucketName: "Camera",
          size: 1,
          hash: hash("x"),
          hashAlgorithm: "sha256",
        },
      ],
      padding: "x".repeat(17 * 1024 * 1024),
    },
  });
  assert.equal(result.status, 200);
  assert.equal(result.imported, 1);
});

test("retrying enrollment after a lost response keeps the same credential and folder", async (t) => {
  const f = await fixture(t),
    clientId = id(),
    credential = crypto.randomBytes(32).toString("base64url");
  const body = {
    clientId,
    credential,
    deviceName: "Mamá 📷",
    pairingCode: "abcdef",
  };
  const first = await f.request("/pair", { method: "POST", body });
  const retry = await f.request("/pair", { method: "POST", body });
  assert.equal(first.status, 200);
  assert.equal(retry.credential, credential);
  assert.equal(first.deviceFolder, retry.deviceFolder);
  await f.request(`/v2/devices/${clientId}`, {
    admin: true,
    method: "PATCH",
    body: { name: "Desktop alias" },
  });
  await f.request("/v2/identity", {
    token: credential,
    headers: { "X-Device-Name-Encoded": encodeURIComponent(body.deviceName) },
  });
  assert.equal(f.store.device(clientId).name, "Desktop alias");
  await f.request("/v2/identity", {
    token: credential,
    headers: {
      "X-Device-Name-Encoded": encodeURIComponent("Mamá's new phone 📷"),
    },
  });
  assert.equal(f.store.device(clientId).name, "Mamá's new phone 📷");
  assert.equal(f.store.device(clientId).folder, first.deviceFolder);
});

test("activity remains capped after restart without losing inventory or receipt identity", async (t) => {
  const f = await fixture(t),
    p = await f.pair();
  f.store.transaction(() => {
    const insert = f.store.q(
      "INSERT INTO activity(library,device,name,album,kind,size,time,data) VALUES(?,?,?,?,?,?,?,?)",
    );
    for (let i = 0; i < 5005; i++)
      insert.run(
        f.store.libraryId,
        p.clientId,
        "old.jpg",
        "Camera",
        "photo",
        1,
        i,
        "{}",
      );
  });
  await f.restart();
  const saved = f.store.saveMedia({
    deviceId: p.clientId,
    fileName: "new.jpg",
    bucketName: "Camera",
    relativePath: "new.jpg",
    hash: hash("x"),
    size: 1,
  });
  const history = f.store.query("activity");
  assert.equal(history.totalCount, 5000);
  assert.equal(history.items[0].receiptId, saved.receiptId);
  assert.equal(f.store.query("media").totalCount, 1);
  f.store.clearHistory();
  assert.equal(f.store.query("activity").totalCount, 0);
  assert.equal(f.store.query("media").totalCount, 1);
});
