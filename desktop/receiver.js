const express = require("express");
const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");
const os = require("node:os");
const {
  LibraryStore,
  component,
  fileComponent,
  inside,
  bounded,
} = require("./storage");
const { adoptLegacy, recoverLegacy } = require("./legacy");
const MAX_FILE = 16 * 1024 ** 3,
  MAX_CHUNK = 4 * 1024 ** 2,
  UPLOAD_TTL = 7 * 86400000;
const ID = /^[a-zA-Z0-9_-]{8,100}$/;
const JOB_STATES = new Set([
  "planning",
  "running",
  "paused",
  "waiting",
  "completed",
  "failed",
  "cancelled",
]);
const fail = (status, message, code) => Object.assign(new Error(message), { status, code });
async function hashFile(file, algorithm = "sha256", onProgress) {
  const h = crypto.createHash(algorithm);
  for await (const c of fs.createReadStream(file)) {
    h.update(c);
    onProgress?.(c.length);
  }
  return h.digest("hex");
}
function equal(a, b) {
  const x = Buffer.from(String(a || "")),
    y = Buffer.from(String(b || ""));
  return x.length > 0 && x.length === y.length && crypto.timingSafeEqual(x, y);
}
function syncDirectory(directory) {
  // Node cannot open Windows directory handles for fsync. File data is already synced per chunk.
  if (process.platform === "win32") return;
  const fd = fs.openSync(directory, "r");
  try {
    fs.fsyncSync(fd);
  } finally {
    fs.closeSync(fd);
  }
}
function getLocalIPs() {
  return [
    ...new Set(
      Object.values(os.networkInterfaces())
        .flat()
        .filter((a) => a && a.family === "IPv4" && !a.internal)
        .map((a) => a.address),
    ),
  ];
}

/** Local-only receiver. Run in a utility process, never on Electron's window/tray thread. */
function createServer(downloadPath, options = {}) {
  const store = new LibraryStore(
    options.databasePath ||
      `${options.historyStatePath || path.join(downloadPath, ".pherry", "state")}.sqlite`,
    downloadPath,
  );
  const root = store.root;
  fs.mkdirSync(inside(root, path.join(".pherry", "uploads")), {
    recursive: true,
  });
  const migrationKey = `legacy:${store.libraryId}`;
  if (
    !store.meta(migrationKey) &&
    options.historyStatePath &&
    fs.existsSync(options.historyStatePath)
  ) {
    try {
      store.importLegacy(
        JSON.parse(fs.readFileSync(options.historyStatePath, "utf8")),
      );
      store.meta(migrationKey, "done");
    } catch (e) {
      console.error("Legacy index migration failed:", e.message);
    }
  }
  const app = express();
  app.disable("x-powered-by");
  app.use((req, res, next) => {
    if (req.headers.origin)
      return res.status(403).json({ error: "Browser origins are not allowed" });
    res.set("Cache-Control", "no-store");
    next();
  });
  const startedAt = Date.now(),
    locks = new Set(),
    hashes = new Map(),
    active = new Map(),
    heartbeats = new Map(),
    attempts = new Map();
  let rebuild = {
    running: false,
    indexed: 0,
    total: 0,
    done: false,
    error: null,
  };
  let pairingCode =
    options.pairingToken || crypto.randomBytes(6).toString("hex");
  const emit = (name, data) => {
    try {
      options[name]?.(data);
    } catch (e) {
      console.error("Receiver event:", e.message);
    }
  };
  const freeBytes = () => {
    try {
      const s = fs.statfsSync(root);
      return Number(s.bavail) * Number(s.bsize);
    } catch {
      return null;
    }
  };
  const owned = (u, req) => {
    if (!u || (!req.admin && u.deviceId !== req.device.id))
      throw fail(404, "Not found");
    return u;
  };
  const admin = (req, res, next) =>
    req.admin ? next() : res.status(403).json({ error: "Desktop action only" });
  const receipt = (e, uploadId) =>
    e
      ? {
          ...e,
          complete: true,
          receiptId: String(e.id),
          savedUploadId: e.uploadId,
          ...(uploadId
            ? { uploadId, deduplicated: uploadId !== e.uploadId }
            : {}),
        }
      : null;
  const onDisk = (e) => {
    if (e.integrityInvalid) return false;
    try {
      const s = fs.statSync(inside(root, e.relativePath));
      return (
        s.isFile() &&
        s.size === e.size &&
        (e.diskMtimeMs == null || s.mtimeMs === e.diskMtimeMs)
      );
    } catch {
      return false;
    }
  };
  const match = (device, hash) => store.find(device, hash).find(onDisk);
  const jobs = () => {
    const pending = store.pendingUploads();
    return store.jobs().map((j) => ({
      ...j,
      receivedBytes:
        (j.completedBytes || 0) +
        pending
          .filter((u) => u.jobId === j.id)
          .reduce((s, u) => s + (active.get(u.uploadId)?.bytes ?? u.offset), 0),
      currentSpeedBytesPerSec: [...active.values()]
        .filter((u) => u.jobId === j.id)
        .reduce((s, u) => s + u.rate, 0),
    }));
  };
  const currentReceipt = (u) => {
    const e = store.file(u.receiptId);
    return e &&
      e.deviceId === u.deviceId &&
      e.hash === u.hash &&
      e.size === u.size &&
      onDisk(e)
      ? e
      : null;
  };
  const expired = () =>
    Object.assign(
      fail(410, "The saved file changed or was removed. Restart this upload."),
      { code: "UPLOAD_EXPIRED" },
    );
  const notifyJobs = () => emit("onJobsChanged", { items: jobs() });
  const tempPath = (u) =>
    inside(root, path.join(".pherry", "uploads", `${u.uploadId}.part`));
  const removePartial = (u) => {
    if (locks.has(u.uploadId)) throw Object.assign(fail(409, "This upload is busy"), { code: "UPLOAD_BUSY" });
    try { fs.unlinkSync(tempPath(u)); } catch (error) { if (error.code !== "ENOENT") throw error; }
    hashes.delete(u.uploadId);
    store.removeUpload(u.uploadId);
  };
  const cleanupExpiredUploads = (now = Date.now()) => {
    const ttl = options.uploadTtlMs ?? UPLOAD_TTL;
    for (const u of store.pendingUploads()) {
      if (locks.has(u.uploadId) || u.finalPath || now - u.updatedAt <= ttl) continue;
      try { removePartial(u); } catch (error) { console.error("Partial cleanup:", error.code || error.message); }
    }
  };
  const saveJob = (j) => {
    if (["completed", "failed", "cancelled", "paused"].includes(j.state)) heartbeats.delete(j.id);
    const saved = store.saveJob(j);
    notifyJobs();
    return saved;
  };
  app.get("/health", (req, res) =>
    res.json({
      status: "ok",
      name: os.hostname(),
      serverName: os.hostname(),
      deviceId: options.deviceId || store.libraryId,
      libraryId: store.libraryId,
      apiVersion: 2,
      requiresPairing: true,
      maxChunkBytes: MAX_CHUNK,
    }),
  );
  app.post("/pair", express.json({ limit: "8kb" }), (req, res) => {
    const key = req.socket.remoteAddress,
      now = Date.now();
    let a = attempts.get(key);
    if (!a || now - a.start > 60000) {
      a = { start: now, count: 0 };
      attempts.set(key, a);
    }
    if (++a.count > 10)
      throw fail(429, "Too many pairing attempts. Try again in a minute.");
    if (!equal(req.body.pairingCode, pairingCode))
      throw fail(401, "Incorrect pairing code", "PAIRING_CODE_INVALID");
    const id = String(req.body.clientId || "");
    if (!ID.test(id) || id === "legacy")
      throw fail(400, "Invalid phone identity");
    const existing = store.device(id);
    if (
      existing &&
      !equal(
        existing.tokenHash,
        crypto
          .createHash("sha256")
          .update(String(req.body.credential || ""))
          .digest("hex"),
      )
    )
      throw fail(
        409,
        "This phone identity is already paired. Use its existing credential or pair a new phone identity.",
      );
    const p = store.pair(
      id,
      String(req.body.deviceName || "Phone"),
      req.body.credential,
    );
    emit("onDevicesChanged", { items: store.devices() });
    res.json({
      credential: p.credential,
      deviceId: options.deviceId || store.libraryId,
      libraryId: store.libraryId,
      deviceFolder: p.folder,
      deviceName: p.name,
      apiVersion: 2,
    });
  });
  app.all(["/upload", "/exists", "/sync/delete"], (req, res) =>
    res.status(426).json({ code: "PROTOCOL_UPDATE_REQUIRED", error: "Update Pherry on this phone to continue backing up" }));
  app.use((req, res, next) => {
    req.admin =
      ["127.0.0.1", "::1", "::ffff:127.0.0.1"].includes(
        req.socket.remoteAddress,
      ) && equal(req.get("X-Pherry-Admin"), options.adminToken);
    if (req.admin) return next();
    req.device = store.authenticate(
      String(req.get("Authorization") || "").replace(/^Bearer /, ""),
    );
    if (!req.device)
      return res.status(401).json({
        error: "Pair this phone with the desktop first",
        code: "PAIRING_REQUIRED",
      });
    if (
      (req.get("X-Pherry-Library") &&
        req.get("X-Pherry-Library") !== store.libraryId) ||
      (req.get("X-Pherry-Receiver") &&
        req.get("X-Pherry-Receiver") !== (options.deviceId || store.libraryId))
    )
      throw fail(
        409,
        "The computer or destination folder changed. Reconnect and review the destination before continuing.",
        "DESTINATION_CHANGED",
      );
    let reportedName = req.get("X-Device-Name");
    if (req.get("X-Device-Name-Encoded")) {
      try {
        reportedName = decodeURIComponent(
          req.get("X-Device-Name-Encoded").replace(/\+/g, " "),
        );
      } catch {
        throw fail(400, "Invalid phone name encoding");
      }
    }
    if (store.observeDeviceName(req.device.id, reportedName)) {
      req.device = store.device(req.device.id);
      emit("onDevicesChanged", { items: store.devices() });
    }
    next();
  });
  // Authenticate before accepting large bodies; only desktop imports need a larger bound.
  app.use("/history/import", admin, express.json({ limit: "64mb" }));
  app.use(express.json({ limit: "512kb" }));
  app.get("/v2/identity", (req, res) =>
    res.json({
      deviceId: options.deviceId || store.libraryId,
      libraryId: store.libraryId,
      clientId: req.device?.id,
      deviceFolder: req.device?.folder,
      deviceName: req.device?.name,
    }),
  );
  app.get("/status", (req, res) => {
    const mine = req.admin ? null : req.device.id,
      totals = store.totals(),
      own = mine ? store.query("media", { limit: 1 }, mine) : null;
    res.json({
      ...totals,
      ...(own
        ? {
            mediaCount: own.totalCount,
            mediaBytes: own.totalBytes,
            totalReceived: own.totalCount,
            totalBytes: own.totalBytes,
          }
        : {}),
      uptimeMs: Date.now() - startedAt,
      libraryId: store.libraryId,
      downloadPath: req.admin ? root : req.device.folder,
      freeBytes: freeBytes(),
      recentActivity: store.query("activity", { limit: 50 }, mine).items,
      jobs: jobs().filter((j) => !mine || j.deviceId === mine),
      devices: req.admin ? store.devices() : undefined,
      receiving: [...active.values()].filter(
        (u) => !mine || u.deviceId === mine,
      ),
      currentSpeedBytesPerSec: [...active.values()]
        .filter((u) => !mine || u.deviceId === mine)
        .reduce((s, u) => s + u.rate, 0),
    });
  });
  app.get("/history", (req, res) =>
    res.json(
      store.query("activity", req.query, req.admin ? null : req.device.id),
    ),
  );
  app.get("/v2/media", (req, res) =>
    res.json(store.query("media", req.query, req.admin ? null : req.device.id)),
  );
  app.get("/v2/jobs", (req, res) =>
    res.json({
      items: jobs().filter((j) => req.admin || j.deviceId === req.device.id),
    }),
  );
  app.get("/v2/jobs/:id", (req, res) =>
    res.json(owned(store.job(req.params.id), req)),
  );
  app.put("/v2/jobs/:id", (req, res) => {
    if (req.admin) throw fail(400, "Start backups from a phone");
    const id = req.params.id;
    if (!ID.test(id)) throw fail(400, "Invalid job ID");
    const old = store.job(id);
    if (old) owned(old, req);
    const collision = store
      .q("SELECT device,library FROM jobs WHERE id=?")
      .get(id);
    if (
      collision &&
      (collision.device !== req.device.id ||
        collision.library !== store.libraryId)
    )
      throw fail(409, "Job identity is already in use", "JOB_ID_CONFLICT");
    const b = req.body,
      state = JOB_STATES.has(b.state) ? b.state : old?.state || "planning";
    const j = {
      ...old,
      id,
      jobId: id,
      deviceId: req.device.id,
      deviceName: req.device.name,
      deviceFolder: req.device.folder,
      startedAt: old?.startedAt || Date.now(),
      state,
      error: String(b.error || "").slice(0, 300),
    };
    for (const k of [
      "totalFiles",
      "totalBytes",
      "completedFiles",
      "completedBytes",
      "skippedFiles",
      "failedFiles",
    ])
      j[k] = bounded(b[k], old?.[k] || 0);
    j.savedFiles = old?.savedFiles || 0;
    j.savedBytes = old?.savedBytes || 0;
    j.completedFiles = Math.max(j.completedFiles, j.savedFiles);
    j.completedBytes = Math.max(j.completedBytes, j.savedBytes);
    if (state === "completed") {
      const pending = store.pendingUploads().filter((u) => u.jobId === id);
      // A concurrent identical file can make a partial unnecessary; it must not block finalization.
      for (const u of pending) {
        if (!locks.has(u.uploadId) && match(u.deviceId, u.hash)?.size === u.size) removePartial(u);
      }
      const unfinished = store.pendingUploads().filter((u) => u.jobId === id);
      if (
        unfinished.length ||
        j.completedFiles + j.skippedFiles + j.failedFiles < j.totalFiles
      )
        return res.status(409).json({ code: "JOB_HAS_PENDING_UPLOADS", error: "The backup still has unfinished files", uploadIds: unfinished.map((u) => u.uploadId) });
      j.completedAt = old?.completedAt || Date.now();
    }
    res.json(saveJob(j));
  });
  app.post("/v2/preflight", (req, res) => {
    cleanupExpiredUploads();
    const required = bounded(req.body.totalBytes, 0),
      free = freeBytes(),
      reserved = store
        .pendingUploads()
        .filter((u) => req.admin || u.deviceId !== req.device.id)
        .reduce((s, u) => s + Math.max(0, u.size - u.offset), 0);
    res.json({
      libraryId: store.libraryId,
      ...store.q("SELECT COUNT(*) AS legacyMediaCount,COALESCE(SUM(size),0) AS legacyBytes FROM media WHERE library=? AND device='legacy'").get(store.libraryId),
      freeBytes: free,
      reservedBytes: reserved,
      requiredBytes: required,
      enoughSpace:
        free === null ? null : required <= Math.max(0, free - reserved),
      deviceFolder: req.device?.folder,
      downloadPath: req.admin ? root : req.device?.folder,
    });
  });
  app.post("/v2/files/exists", async (req, res) => {
    if (req.admin) throw fail(400, "Select a phone");
    if (!Array.isArray(req.body.hashes) || req.body.hashes.length > 500)
      throw fail(400, "Send at most 500 hashes");
    const files = [];
    for (const raw of req.body.hashes) {
      const hash = String(raw).toLowerCase();
      if (!/^(?:[a-f0-9]{32}|[a-f0-9]{64})$/.test(hash))
        throw fail(400, "Invalid content hash");
      const e = match(req.device.id, hash);
      let exists = !!e;
      if (e && req.body.verify === true) {
        exists =
          (await hashFile(inside(root, e.relativePath), e.hashAlgorithm)) ===
          hash;
        if (!exists) store.invalidateFile(e.id);
      }
      files.push({ hash, exists, ...(exists ? receipt(e) : {}) });
    }
    res.json({
      files,
      libraryId: store.libraryId,
      verification: req.body.verify === true ? "checksum" : "presence",
    });
  });
  app.post("/v2/uploads", async (req, res) => {
    if (req.admin) throw fail(400, "Uploads belong to paired phones");
    const b = req.body,
      uploadId = String(b.uploadId || ""),
      jobId = String(b.jobId || ""),
      hash = String(b.hash || "").toLowerCase();
    if (
      !ID.test(uploadId) ||
      !ID.test(jobId) ||
      !/^([a-f0-9]{64})$/.test(hash) ||
      b.hashAlgorithm !== "sha256"
    )
      throw fail(400, "Invalid upload identity or SHA-256");
    if (!Number.isSafeInteger(b.size) || b.size < 0 || b.size > MAX_FILE)
      throw fail(413, "File exceeds the 16 GB limit");
    const job = owned(store.job(jobId), req);
    if (["completed", "cancelled"].includes(job.state))
      throw fail(409, "This backup has finished", "JOB_FINISHED");
    let u = store.upload(uploadId);
    if (u) {
      owned(u, req);
      if (u.hash !== hash || u.size !== b.size || u.jobId !== jobId)
        throw fail(409, "Upload identity does not match this file", "UPLOAD_ID_CONFLICT");
      const saved = u.complete ? currentReceipt(u) : null;
      if (!u.complete || saved) {
        if (!u.complete) { u.updatedAt = Date.now(); store.saveUpload(u); }
        return res.json({ ...u, ...(saved ? receipt(saved, uploadId) : {}) });
      }
      store.removeUpload(uploadId);
      u = null;
    }
    if (store.q("SELECT id FROM uploads WHERE id=?").get(uploadId))
      throw fail(409, "Upload identity is already in use", "UPLOAD_ID_CONFLICT");
    if (locks.has(uploadId)) throw fail(409, "This upload is busy", "UPLOAD_BUSY");
    const e = match(req.device.id, hash);
    if (e) {
      u = {
        uploadId,
        deviceId: req.device.id,
        jobId,
        hash,
        size: b.size,
        offset: b.size,
        complete: true,
        receiptId: e.id,
        updatedAt: Date.now(),
      };
      store.saveUpload(u);
      return res.json({ ...u, ...receipt(e, uploadId) });
    }
    u = {
      uploadId,
      jobId,
      deviceId: req.device.id,
      deviceFolder: req.device.folder,
      hash,
      hashAlgorithm: "sha256",
      size: b.size,
      fileName: fileComponent(b.fileName),
      originalName: String(b.fileName || "Untitled").slice(0, 200),
      bucketName: component(b.bucketName),
      mimeType: String(b.mimeType || ""),
      sourceTimestampMs: bounded(b.sourceTimestampMs, 0),
      offset: 0,
      complete: false,
      updatedAt: Date.now(),
    };
    if (b.adoptLegacy === true) {
      locks.add(uploadId);
      try {
        const adopted = await adoptLegacy(store, u);
        if (adopted) {
          u = { ...u, offset: u.size, complete: true, receiptId: adopted.id, adoptedLegacy: true };
          store.saveUpload(u);
          emit("onFilesRemoved", { source: "legacy-adoption", deviceId: req.device.id });
          return res.json({ ...u, ...receipt(adopted, uploadId) });
        }
      } finally { locks.delete(uploadId); }
    }
    cleanupExpiredUploads();
    const free = freeBytes(), reserved = store.pendingUploads().reduce((s, x) => s + x.size - x.offset, 0);
    if (free !== null && b.size > Math.max(0, free - reserved))
      throw fail(507, "Not enough free space on the computer", "OUT_OF_SPACE");
    fs.writeFileSync(tempPath(u), "", { flag: "wx" });
    store.saveUpload(u);
    res.status(201).json(u);
  });
  app.delete("/v2/uploads/:id", async (req, res) => {
    const u = owned(store.upload(req.params.id), req);
    if (locks.has(u.uploadId))
      throw fail(409, "A chunk is still being written", "UPLOAD_BUSY");
    if (!u.complete)
      await fs.promises.unlink(tempPath(u)).catch((e) => {
        if (e.code !== "ENOENT") throw e;
      });
    hashes.delete(u.uploadId);
    store.removeUpload(u.uploadId);
    res.json({ success: true });
  });
  app.get("/v2/uploads/:id", (req, res) => {
    const u = owned(store.upload(req.params.id), req);
    const saved = u.complete ? currentReceipt(u) : null;
    if (u.complete && !saved) throw expired();
    res.json({ ...u, ...(saved ? receipt(saved, u.uploadId) : {}) });
  });
  app.patch("/v2/uploads/:id", async (req, res) => {
    const u = owned(store.upload(req.params.id), req);
    if (u.complete) {
      if (!currentReceipt(u)) throw expired();
      return res.json({ uploadId: u.uploadId, offset: u.size, complete: true });
    }
    if (locks.has(u.uploadId))
      throw fail(409, "Another chunk is being written", "UPLOAD_BUSY");
    const offset = Number(req.get("Upload-Offset")),
      length = Number(req.get("Content-Length"));
    if (!Number.isSafeInteger(offset) || offset !== u.offset)
      return res
        .status(409)
        .json({ code: "OFFSET_CHANGED", error: "Offset changed", offset: u.offset });
    if (
      !Number.isSafeInteger(length) ||
      length <= 0 ||
      length > MAX_CHUNK ||
      offset + length > u.size
    )
      throw fail(413, "Invalid chunk size");
    locks.add(u.uploadId);
    const file = tempPath(u);
    let handle,
      written = 0;
    try {
      handle = await fs.promises.open(file, "r+");
      await handle.truncate(offset);
      let state = hashes.get(u.uploadId);
      if (!state || state.offset !== offset) {
        const h = crypto.createHash("sha256");
        if (offset)
          for await (const c of fs.createReadStream(file, {
            start: 0,
            end: offset - 1,
          }))
            h.update(c);
        state = { hash: h, offset };
        hashes.set(u.uploadId, state);
        if (hashes.size > 64) hashes.delete(hashes.keys().next().value);
      }
      const began = Date.now(),
        progress = {
          id: u.uploadId,
          jobId: u.jobId,
          deviceId: u.deviceId,
          device: store.device(u.deviceId).name,
          fileName: u.fileName,
          bytes: offset,
          total: u.size,
          startedAt: began,
          rate: 0,
        };
      active.set(u.uploadId, progress);
      for await (const c of req) {
        if (written + c.length > length) throw fail(413, "Chunk is too large");
        let at = 0;
        while (at < c.length) {
          const r = await handle.write(
            c,
            at,
            c.length - at,
            offset + written + at,
          );
          if (!r.bytesWritten) throw new Error("Disk write stopped");
          at += r.bytesWritten;
        }
        state.hash.update(c);
        written += c.length;
        progress.bytes = offset + written;
        progress.rate = Math.round(
          (written * 1000) / Math.max(1, Date.now() - began),
        );
      }
      if (written !== length) throw fail(400, "Chunk was interrupted");
      await handle.sync();
      u.offset = offset + written;
      u.updatedAt = Date.now();
      state.offset = u.offset;
      store.saveUpload(u);
      heartbeats.set(u.jobId, Date.now());
      // Acknowledgement also means the file handle and chunk lock are released for commit.
      await handle.close();
      handle = null;
      locks.delete(u.uploadId);
      active.delete(u.uploadId);
      res.json({ uploadId: u.uploadId, offset: u.offset, complete: false });
    } catch (e) {
      hashes.delete(u.uploadId);
      if (handle) await handle.truncate(offset).catch(() => {});
      throw e;
    } finally {
      if (handle) await handle.close();
      locks.delete(u.uploadId);
      active.delete(u.uploadId);
    }
  });
  // Rename is a durable intent, even if the following database write fails while we stay alive.
  // A retry must finish that receipt instead of trying to read the now-moved partial forever.
  const recoverCommittedUpload = async (u) => {
    let final, before;
    try {
      final = inside(root, u.finalPath);
      before = await fs.promises.stat(final);
    } catch (error) {
      if (error.code === "ENOENT") throw expired();
      throw error;
    }
    if (!before.isFile() || before.size !== u.size || (await hashFile(final)) !== u.hash) throw expired();
    const after = await fs.promises.stat(final);
    if (after.size !== before.size || after.mtimeMs !== before.mtimeMs || after.ino !== before.ino) throw expired();
    const existing = store.find(u.deviceId, u.hash).find((entry) => entry.relativePath === u.finalPath);
    const saved = existing && onDisk(existing) ? existing : store.saveMedia({
      ...u, relativePath: u.finalPath, fileName: path.basename(final), time: existing?.time || u.updatedAt,
      integrityInvalid: false, diskMtimeMs: after.mtimeMs, diskCtimeMs: after.ctimeMs,
    }, { arrival: !existing });
    store.accountSavedUpload(u);
    u.complete = true; u.receiptId = saved.id; u.updatedAt = Date.now();
    store.saveUpload(u);
    notifyJobs(); emit("onFileReceived", saved);
    return saved;
  };
  app.post("/v2/uploads/:id/complete", async (req, res) => {
    const u = owned(store.upload(req.params.id), req);
    if (u.complete) {
      const saved = currentReceipt(u);
      if (!saved) throw expired();
      return res.json({
        ...receipt(saved, u.uploadId),
        uploadId: u.uploadId,
        offset: u.size,
      });
    }
    if (locks.has(u.uploadId))
      throw fail(409, "A chunk is still being written", "UPLOAD_BUSY");
    if (u.offset !== u.size) throw fail(409, "The file is not fully uploaded", "UPLOAD_INCOMPLETE");
    locks.add(u.uploadId);
    try {
      if (u.finalPath && !fs.existsSync(tempPath(u))) {
        const saved = await recoverCommittedUpload(u);
        return res.json({ ...receipt(saved, u.uploadId), offset: u.size });
      }
      const file = tempPath(u),
        state = hashes.get(u.uploadId);
      hashes.delete(u.uploadId);
      const actual =
        state?.offset === u.size
          ? state.hash.digest("hex")
          : await hashFile(file);
      if (actual !== u.hash) {
        await fs.promises.truncate(file, 0);
        u.offset = 0;
        store.saveUpload(u);
        throw fail(422, "Checksum mismatch. Restart this file.", "CHECKSUM_MISMATCH");
      }
      let saved = match(u.deviceId, u.hash);
      if (saved) {
        await fs.promises.unlink(file);
      } else {
        // No await between deduplication, filename allocation and commit: simultaneous copies share one receipt.
        const dir = inside(root, path.join(u.deviceFolder, u.bucketName));
        const created = fs.mkdirSync(dir, { recursive: true });
        let name = u.fileName,
          n = 1;
        const ext = path.extname(name),
          stem = path.basename(name, ext);
        while (fs.existsSync(path.join(dir, name)))
          name = `${stem} (${n++})${ext}`;
        const final = inside(root, path.relative(root, path.join(dir, name)));
        u.finalPath = path.relative(root, final);
        store.saveUpload(u);
        fs.renameSync(file, final);
        syncDirectory(dir);
        if (created) {
          for (
            let parent = path.dirname(dir);
            parent === root || parent.startsWith(root + path.sep);
            parent = path.dirname(parent)
          ) {
            syncDirectory(parent);
            if (parent === root) break;
          }
        }
        if (u.sourceTimestampMs > 0)
          try {
            fs.utimesSync(final, new Date(), new Date(u.sourceTimestampMs));
          } catch {
            /* Bytes are already safe; some filesystems reject source timestamps. */
          }
        saved = store.saveMedia({
          ...u,
          fileName: name,
          relativePath: u.finalPath,
          size: u.size,
          time: Date.now(),
          diskMtimeMs: fs.statSync(final).mtimeMs,
          diskCtimeMs: fs.statSync(final).ctimeMs,
        });
        store.accountSavedUpload(u);
        notifyJobs();
        emit("onFileReceived", saved);
      }
      u.complete = true;
      u.receiptId = saved.id;
      u.updatedAt = Date.now();
      store.saveUpload(u);
      res.json({ ...receipt(saved, u.uploadId), offset: u.size });
    } finally {
      locks.delete(u.uploadId);
    }
  });
  app.post("/v2/sync/delete", async (req, res) => {
    if (req.admin) throw fail(400, "Mirror deletion belongs to a phone");
    const entries = req.body.entries;
    if (!Array.isArray(entries) || entries.length > 500)
      throw fail(400, "Send at most 500 deletion entries");
    const results = [];
    let deleted = 0,
      bytesFreed = 0;
    for (const item of entries) {
      const hash = String(item.hash || item.md5 || ""),
        candidates = store
          .find(req.device.id, hash)
          .filter(
            (e) => !item.receiptId || String(e.id) === String(item.receiptId),
          );
      let error = null,
        count = 0;
      for (const e of candidates) {
        try {
          const file = inside(root, e.relativePath);
          const before = await fs.promises.stat(file);
          if ((await hashFile(file, e.hashAlgorithm || "sha256")) !== e.hash)
            throw new Error("The computer copy changed. It was kept.");
          const after = await fs.promises.stat(file);
          if (
            before.size !== after.size ||
            before.mtimeMs !== after.mtimeMs ||
            before.ctimeMs !== after.ctimeMs
          )
            throw new Error(
              "The computer copy changed during verification. It was kept.",
            );
          await fs.promises.unlink(file);
          store.forgetFile(e.id);
          count++;
          bytesFreed += e.size;
        } catch (err) {
          if (err.code === "ENOENT") store.forgetFile(e.id);
          else error = err.code || err.message || "Cannot delete this file";
        }
      }
      deleted += count;
      results.push({
        hash,
        deleted: !error && count > 0,
        missing: !error && count === 0,
        error,
      });
    }
    if (deleted)
      emit("onFilesRemoved", { deleted, bytesFreed, deviceId: req.device.id });
    res.json({
      success: results.every((r) => !r.error),
      deleted,
      bytesFreed,
      results,
    });
  });
  app.get("/v2/devices", admin, (req, res) =>
    res.json({ items: store.devices() }),
  );
  app.patch("/v2/devices/:id", admin, (req, res) => {
    if (!String(req.body.name || "").trim())
      throw fail(400, "Enter a phone name");
    store.renameDevice(req.params.id, req.body.name);
    emit("onDevicesChanged", { items: store.devices() });
    res.json({ success: true, items: store.devices() });
  });
  app.delete("/v2/devices/:id", admin, (req, res) => {
    store.revokeDevice(req.params.id);
    emit("onDevicesChanged", { items: store.devices() });
    res.json({ success: true });
  });
  app.post("/v2/pairing-code", admin, (req, res) => {
    if (!String(req.body.code || "").trim())
      throw fail(400, "Pairing code required");
    pairingCode = String(req.body.code);
    res.json({ success: true });
  });
  app.post("/history/clear", admin, (req, res) => {
    store.clearHistory();
    res.json({ success: true });
  });
  app.get("/history/export", admin, (req, res) =>
    res.json(store.exportHistory()),
  );
  app.post("/history/import", admin, (req, res) =>
    res.json({ success: true, imported: store.importLegacy(req.body) }),
  );
  app.get("/history/rebuild-progress", admin, (req, res) =>
    res.json({ success: true, ...rebuild }),
  );
  app.post("/history/rebuild-index", admin, (req, res) => {
    if (rebuild.running)
      return res.json({ success: true, started: false, alreadyRunning: true });
    rebuild = { running: true, indexed: 0, total: 0, done: false, error: null };
    (async () => {
      const files = [];
      const walk = async (dir) => {
        for (const e of await fs.promises.readdir(dir, {
          withFileTypes: true,
        })) {
          if (e.name === ".pherry" || e.isSymbolicLink()) continue;
          const f = path.join(dir, e.name);
          if (e.isDirectory()) await walk(f);
          else if (e.isFile() && !e.name.endsWith(".part")) files.push(f);
        }
      };
      await walk(root);
      rebuild.total = files.length;
      const ds = store.devices();
      for (const f of files) {
        const relative = path.relative(root, f),
          segments = relative.split(path.sep),
          d = ds.find((d) => d.deviceFolder && d.deviceFolder === segments[0]),
          s = await fs.promises.stat(f),
          hash = await hashFile(f);
        store.saveMedia(
          {
            deviceId: d?.deviceId || "legacy",
            relativePath: relative,
            fileName: path.basename(f),
            bucketName:
              (d ? segments.slice(1, -1) : segments.slice(0, -1)).join("/") ||
              "Unsorted",
            size: s.size,
            hash,
            hashAlgorithm: "sha256",
            time: s.mtimeMs,
            diskMtimeMs: s.mtimeMs,
            diskCtimeMs: s.ctimeMs,
          },
          { arrival: false },
        );
        rebuild.indexed++;
      }
      // Only a completed walk may prune missing entries. Permission errors preserve receipts.
      for (const e of store.exportHistory().completedFiles) {
        try {
          await fs.promises.stat(inside(root, e.relativePath));
        } catch (error) {
          if (error.code === "ENOENT") store.forgetFile(e.id);
        }
      }
    })()
      .catch((e) => {
        rebuild.error = e.message;
      })
      .finally(() => {
        rebuild.running = false;
        rebuild.done = true;
        emit("onFilesRemoved", { source: "rebuild" });
      });
    res.json({ success: true, started: true });
  });
  app.post("/history/remove-duplicates", admin, async (req, res) => {
    const groups = new Map();
    const seenPaths = new Set();
    for (const e of store.exportHistory().completedFiles) {
      if (!e.hash || !onDisk(e)) continue;
      const real = fs.realpathSync(inside(root, e.relativePath));
      const canonical =
        process.platform === "win32" ? real.toLowerCase() : real;
      // Imported/case-renamed records can point at one physical file. They are not two copies.
      if (seenPaths.has(canonical)) continue;
      seenPaths.add(canonical);
      const k = `${e.deviceId}:${e.hash}`;
      if (!groups.has(k)) groups.set(k, []);
      groups.get(k).push(e);
    }
    const verified = [];
    const failures = [];
    for (const group of groups.values()) {
      if (group.length < 2) continue;
      const current = [];
      for (const e of group) {
        try {
          const file = inside(root, e.relativePath);
          const before = await fs.promises.stat(file);
          const digest = await hashFile(file, e.hashAlgorithm || "sha256");
          const after = await fs.promises.stat(file);
          if (
            digest === e.hash &&
            before.size === after.size &&
            before.mtimeMs === after.mtimeMs &&
            before.ctimeMs === after.ctimeMs
          )
            current.push({
              ...e,
              diskMtimeMs: after.mtimeMs,
              diskCtimeMs: after.ctimeMs,
            });
        } catch (error) {
          failures.push({
            fileName: e.fileName,
            error: error.code || error.message,
          });
        }
      }
      if (current.length > 1) verified.push(current);
    }
    const dup = verified.flatMap((g) => g.slice(1));
    let deleted = 0,
      bytesFreed = 0;
    if (!req.body.dryRun)
      for (const group of verified)
        for (const e of group.slice(1)) {
          try {
            if (!onDisk(group[0]) || !onDisk(e))
              throw new Error("File changed during cleanup; kept both copies");
            await fs.promises.unlink(inside(root, e.relativePath));
            store.forgetFile(e.id);
            deleted++;
            bytesFreed += e.size;
          } catch (err) {
            failures.push({
              fileName: e.fileName,
              error: err.code || err.message,
            });
          }
        }
    if (deleted) emit("onFilesRemoved", { deleted, bytesFreed });
    res.json({
      success: !failures.length,
      dryRun: !!req.body.dryRun,
      duplicateFiles: dup.length,
      duplicateBytes: dup.reduce((s, e) => s + e.size, 0),
      removed: req.body.dryRun ? dup.length : deleted,
      groups: verified.length,
      deleted,
      bytesFreed: req.body.dryRun
        ? dup.reduce((s, e) => s + e.size, 0)
        : bytesFreed,
      failures,
    });
  });
  app.use((err, req, res, next) => {
    if (res.headersSent) return next(err);
    res.status(err.status || (err.code === "ENOSPC" ? 507 : 500)).json({
      error: err.status
        ? err.message
        : err.code === "ENOSPC"
          ? "The computer ran out of disk space"
          : "The receiver could not finish this operation",
      code: err.code,
    });
  });
  // Durable intent recovery closes the rename/DB-commit crash window. Chunks roll back to their last acknowledgement.
  const ready = (async () => {
    let lastRecoveryProgress = 0;
    const recoveryProgress = () => {
      if (Date.now() - lastRecoveryProgress < 1000) return;
      lastRecoveryProgress = Date.now();
      emit("onRecoveryProgress", {});
    };
    const legacyRecovery = await recoverLegacy(store, recoveryProgress);
    if (legacyRecovery.pending) console.error("Previous backup moves need review:", legacyRecovery.errors);
    for (const u of store.pendingUploads()) {
      if (u.complete) continue;
      if (u.finalPath) {
          const final = inside(root, u.finalPath);
          let digest;
          try { digest = await hashFile(final, "sha256", recoveryProgress); }
          catch (error) { if (error.code !== "ENOENT") throw error; }
          if (digest === u.hash) {
            const existing = store
              .find(u.deviceId, u.hash)
              .find((e) => e.relativePath === u.finalPath);
            const saved =
              existing ||
              store.saveMedia({
                ...u,
                relativePath: u.finalPath,
                fileName: path.basename(final),
                time: u.updatedAt,
                diskMtimeMs: fs.statSync(final).mtimeMs,
                diskCtimeMs: fs.statSync(final).ctimeMs,
              });
            u.complete = true;
            u.receiptId = saved.id;
            store.accountSavedUpload(u);
            store.saveUpload(u);
            continue;
          }
      }
      if (!u.finalPath && Date.now() - u.updatedAt > (options.uploadTtlMs ?? UPLOAD_TTL)) {
        removePartial(u);
        continue;
      }
      try {
        const h = await fs.promises.open(tempPath(u), "r+"),
          s = await h.stat();
        u.offset = Math.min(u.offset, s.size);
        await h.truncate(u.offset);
        await h.close();
        store.saveUpload(u);
      } catch (e) {
        if (e.code === "ENOENT") {
          u.offset = 0;
          fs.writeFileSync(tempPath(u), "");
          store.saveUpload(u);
        } else throw e;
      }
    }
  })();
  const timer = setInterval(() => {
    cleanupExpiredUploads();
    for (const j of store.jobs())
      if (
        j.state === "running" &&
        Date.now() - Math.max(j.updatedAt, heartbeats.get(j.id) || 0) > 45000 &&
        ![...active.values()].some((u) => u.jobId === j.id)
      )
        saveJob({
          ...j,
          state: "waiting",
          error: "Waiting for the phone to reconnect.",
        });
    for (const [k, a] of attempts)
      if (Date.now() - a.start > 60000) attempts.delete(k);
  }, 10000);
  timer.unref();
  app.locals.ready = ready;
  app.locals.store = store;
  app.locals.cleanupExpiredUploads = cleanupExpiredUploads;
  app.locals.close = () => {
    clearInterval(timer);
    store.close();
  };
  return app;
}
module.exports = { createServer, getLocalIPs, hashFile };
