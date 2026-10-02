const { DatabaseSync } = require("node:sqlite");
const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");

const HISTORY_CAP = 5000;
const uuid = () => crypto.randomUUID();
const bounded = (v, fallback, max = Number.MAX_SAFE_INTEGER) =>
  Number.isFinite(Number(v))
    ? Math.max(0, Math.min(max, Math.floor(Number(v))))
    : fallback;
const json = (row) => (row ? JSON.parse(row.data) : null);
const kind = (name) =>
  /\.(mp4|mov|m4v|3gp|webm|mkv|avi)$/i.test(name)
    ? "video"
    : /\.(jpe?g|png|heic|heif|webp|gif|bmp|tiff?|raw|dng|nef|cr2|arw)$/i.test(
          name,
        )
      ? "photo"
      : "other";

/** A single directory component, portable across Windows/macOS/Linux. */
function component(value, fallback = "Unsorted") {
  const clean = String(value || "")
    .normalize("NFC")
    .replace(/[\x00-\x1f\x7f<>:"/\\|?*]/g, "_")
    .replace(/[. ]+$/g, "")
    .trim()
    .slice(0, 100)
    .replace(/[. ]+$/g, "");
  return !clean ||
    clean === "." ||
    clean === ".." ||
    /^(con|prn|aux|nul|com[0-9]|lpt[0-9])(?:\.|$)/i.test(clean)
    ? fallback
    : clean;
}
function fileComponent(value) {
  const name = component(value, "Untitled");
  const extension = path.extname(String(value || ""));
  // Long camera/share names must retain their media extension for OS viewers and thumbnails.
  return /^\.[a-zA-Z0-9]{1,12}$/.test(extension) && !name.endsWith(extension)
    ? component(name.slice(0, 100 - extension.length), "Untitled") + extension
    : name;
}

/** Reject traversal AND symlink/junction escapes, including a replaced parent directory. */
function inside(root, relative) {
  if (!relative || path.isAbsolute(relative) || /^[a-z]:/i.test(relative))
    throw new Error("Invalid file path");
  const base = fs.realpathSync(root);
  const full = path.resolve(base, relative);
  if (!full.startsWith(base + path.sep))
    throw new Error("File is outside the backup folder");
  let cursor = full;
  while (!fs.existsSync(cursor)) {
    const parent = path.dirname(cursor);
    if (parent === cursor) throw new Error("Invalid file path");
    cursor = parent;
  }
  const real = fs.realpathSync(cursor);
  if (real !== base && !real.startsWith(base + path.sep))
    throw new Error("Linked path is outside the backup folder");
  return full;
}

class LibraryStore {
  constructor(filename, root) {
    fs.mkdirSync(root, { recursive: true });
    fs.mkdirSync(path.dirname(filename), { recursive: true });
    this.root = fs.realpathSync(root);
    this.db = new DatabaseSync(filename);
    this.db
      .exec(`PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL; PRAGMA busy_timeout=5000;
      CREATE TABLE IF NOT EXISTS libraries(id TEXT PRIMARY KEY, root TEXT UNIQUE NOT NULL);
      CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY, name TEXT NOT NULL, folder TEXT UNIQUE NOT NULL, tokenHash TEXT, pairedAt INTEGER, lastSeenAt INTEGER);
      CREATE TABLE IF NOT EXISTS media(id INTEGER PRIMARY KEY AUTOINCREMENT, library TEXT NOT NULL, device TEXT NOT NULL, hash TEXT NOT NULL, algorithm TEXT NOT NULL, relative TEXT NOT NULL, name TEXT NOT NULL, album TEXT NOT NULL, kind TEXT NOT NULL, size INTEGER NOT NULL, time INTEGER NOT NULL, data TEXT NOT NULL, UNIQUE(library,relative));
      CREATE INDEX IF NOT EXISTS media_content ON media(library,device,hash);
      CREATE INDEX IF NOT EXISTS media_browse ON media(library,device,album,time DESC);
      CREATE INDEX IF NOT EXISTS media_kind ON media(library,kind,time DESC);
      CREATE TABLE IF NOT EXISTS activity(id INTEGER PRIMARY KEY AUTOINCREMENT, library TEXT NOT NULL, device TEXT NOT NULL, name TEXT NOT NULL, album TEXT NOT NULL, kind TEXT NOT NULL, size INTEGER NOT NULL, time INTEGER NOT NULL, data TEXT NOT NULL);
      CREATE INDEX IF NOT EXISTS activity_browse ON activity(library,time DESC);
      CREATE TABLE IF NOT EXISTS uploads(id TEXT PRIMARY KEY, library TEXT NOT NULL, device TEXT NOT NULL, job TEXT NOT NULL, updated INTEGER NOT NULL, data TEXT NOT NULL);
      CREATE INDEX IF NOT EXISTS uploads_pending ON uploads(library,json_extract(data,'$.complete'));
      CREATE TABLE IF NOT EXISTS jobs(id TEXT PRIMARY KEY, library TEXT NOT NULL, device TEXT NOT NULL, updated INTEGER NOT NULL, data TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS job_saves(library TEXT NOT NULL, job TEXT NOT NULL, upload TEXT NOT NULL, size INTEGER NOT NULL, PRIMARY KEY(library,job,upload));
      CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL);`);
    this.statements = new Map();
    if (
      !this.db
        .prepare("PRAGMA table_info(devices)")
        .all()
        .some((c) => c.name === "revoked")
    ) {
      this.db.exec(
        "ALTER TABLE devices ADD COLUMN revoked INTEGER NOT NULL DEFAULT 0; UPDATE devices SET revoked=1 WHERE tokenHash IS NULL",
      );
    }
    if (
      !this.db
        .prepare("PRAGMA table_info(devices)")
        .all()
        .some((c) => c.name === "reportedName")
    ) {
      this.db.exec(
        "ALTER TABLE devices ADD COLUMN reportedName TEXT; UPDATE devices SET reportedName=name",
      );
    }
    let library = this.q("SELECT id FROM libraries WHERE root=?").get(
      this.root,
    );
    if (!library) {
      library = { id: uuid() };
      this.q("INSERT INTO libraries VALUES(?,?)").run(library.id, this.root);
    }
    this.libraryId = library.id;
    if (!this.meta("job-saves-v1")) {
      this.db.exec(
        "INSERT OR IGNORE INTO job_saves SELECT library,json_extract(data,'$.jobId'),json_extract(data,'$.uploadId'),size FROM media WHERE json_extract(data,'$.jobId') IS NOT NULL AND json_extract(data,'$.uploadId') IS NOT NULL",
      );
      this.meta("job-saves-v1", "done");
    }
    this.activityCount = this.q(
      "SELECT COUNT(*) AS n FROM activity WHERE library=?",
    ).get(this.libraryId).n;
    this.q(
      "INSERT OR IGNORE INTO devices(id,name,folder,tokenHash,pairedAt,lastSeenAt,revoked) VALUES('legacy','Previous backups','',NULL,0,0,1)",
    ).run();
    for (const job of this.jobs()) {
      if (["running", "planning"].includes(job.state))
        this.saveJob({
          ...job,
          state: "waiting",
          error: "Receiver restarted. Resume this backup from the phone.",
        });
    }
  }
  q(sql) {
    if (!this.statements.has(sql))
      this.statements.set(sql, this.db.prepare(sql));
    return this.statements.get(sql);
  }
  transaction(fn) {
    const activityCount = this.activityCount;
    this.db.exec("BEGIN IMMEDIATE");
    try {
      const result = fn();
      this.db.exec("COMMIT");
      return result;
    } catch (e) {
      this.db.exec("ROLLBACK");
      this.activityCount = activityCount;
      throw e;
    }
  }
  close() {
    this.db.close();
  }
  meta(key, value) {
    if (value !== undefined)
      this.q(
        "INSERT INTO meta VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
      ).run(key, String(value));
    return this.q("SELECT value FROM meta WHERE key=?").get(key)?.value;
  }
  device(id) {
    return this.q("SELECT * FROM devices WHERE id=?").get(id);
  }
  devices() {
    return this.q(
      "SELECT id AS deviceId,name AS deviceName,folder AS deviceFolder,pairedAt,lastSeenAt,revoked FROM devices ORDER BY name",
    )
      .all()
      .map((d) => ({ ...d, id: d.deviceId, revoked: !!d.revoked }));
  }
  pair(id, name, suppliedCredential) {
    const existing = this.device(id);
    // The phone persists its random secret before enrollment, making response loss retryable.
    const credential = /^[A-Za-z0-9_-]{43,128}$/.test(suppliedCredential || "")
      ? suppliedCredential
      : crypto.randomBytes(32).toString("base64url");
    const hash = crypto.createHash("sha256").update(credential).digest("hex");
    const folder =
      existing?.folder ||
      `${component(name, "Phone")} - ${crypto.createHash("sha256").update(id).digest("hex").slice(0, 8)}`;
    // A user-assigned desktop alias survives re-enrollment and phone model-name changes.
    this.q(
      "INSERT INTO devices(id,name,folder,tokenHash,pairedAt,lastSeenAt,reportedName) VALUES(?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET tokenHash=excluded.tokenHash,lastSeenAt=excluded.lastSeenAt,revoked=0",
    ).run(
      id,
      String(name || "Phone").slice(0, 80),
      folder,
      hash,
      Date.now(),
      Date.now(),
      String(name || "Phone").slice(0, 80),
    );
    return { credential, ...this.device(id) };
  }
  authenticate(token) {
    if (!token || token.length > 512) return null;
    const hash = crypto.createHash("sha256").update(token).digest("hex");
    const d = this.q(
      "SELECT * FROM devices WHERE tokenHash=? AND revoked=0",
    ).get(hash);
    if (d && Date.now() - d.lastSeenAt > 30000)
      this.q("UPDATE devices SET lastSeenAt=? WHERE id=?").run(
        Date.now(),
        d.id,
      );
    return d;
  }
  renameDevice(id, name) {
    this.q("UPDATE devices SET name=? WHERE id=?").run(
      String(name).trim().slice(0, 80),
      id,
    );
    return this.device(id);
  }
  observeDeviceName(id, name) {
    const clean = String(name || "")
      .trim()
      .slice(0, 80);
    const d = this.device(id);
    if (!clean || !d || clean === d.reportedName) return false;
    this.q("UPDATE devices SET name=?,reportedName=? WHERE id=?").run(
      clean,
      clean,
      id,
    );
    return true;
  }
  revokeDevice(id) {
    this.q("UPDATE devices SET revoked=1 WHERE id=?").run(id);
  }
  decorate(row, activity = false) {
    if (!row) return null;
    const d = this.device(row.device);
    return {
      ...JSON.parse(row.data),
      id: row.id,
      receiptId: activity ? JSON.parse(row.data).receiptId : String(row.id),
      timestamp: row.time,
      time: row.time,
      deviceId: row.device,
      deviceName: d?.name || "Previous backups",
      deviceFolder: d?.folder || "",
    };
  }
  file(id) {
    return this.decorate(
      this.q("SELECT * FROM media WHERE library=? AND id=?").get(
        this.libraryId,
        id,
      ),
    );
  }
  find(device, hash) {
    return this.q("SELECT * FROM media WHERE library=? AND device=? AND hash=?")
      .all(this.libraryId, device, hash)
      .map((r) => this.decorate(r));
  }
  saveMedia(entry, { arrival = true } = {}) {
    const e = {
      ...entry,
      libraryId: this.libraryId,
      originalName: entry.originalName || entry.fileName,
      kind: kind(entry.fileName),
      time: entry.time || Date.now(),
      status: "saved",
    };
    const run = () => {
      this.q(
        "INSERT INTO media(library,device,hash,algorithm,relative,name,album,kind,size,time,data) VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(library,relative) DO UPDATE SET device=excluded.device,hash=excluded.hash,algorithm=excluded.algorithm,name=excluded.name,album=excluded.album,kind=excluded.kind,size=excluded.size,time=excluded.time,data=excluded.data",
      ).run(
        this.libraryId,
        e.deviceId,
        e.hash,
        e.hashAlgorithm || "sha256",
        e.relativePath,
        e.fileName,
        e.bucketName,
        e.kind,
        e.size,
        e.time,
        JSON.stringify(e),
      );
      const saved = this.decorate(
        this.q("SELECT * FROM media WHERE library=? AND relative=?").get(
          this.libraryId,
          e.relativePath,
        ),
      );
      if (arrival) {
        this.q(
          "INSERT INTO activity(library,device,name,album,kind,size,time,data) VALUES(?,?,?,?,?,?,?,?)",
        ).run(
          this.libraryId,
          e.deviceId,
          e.fileName,
          e.bucketName,
          e.kind,
          e.size,
          e.time,
          JSON.stringify(saved),
        );
        if (++this.activityCount > HISTORY_CAP) {
          this.q(
            "DELETE FROM activity WHERE id IN (SELECT id FROM activity WHERE library=? ORDER BY id LIMIT ?)",
          ).run(this.libraryId, this.activityCount - HISTORY_CAP);
          this.activityCount = HISTORY_CAP;
        }
        this.meta(
          `count:${this.libraryId}`,
          Number(this.meta(`count:${this.libraryId}`) || 0) + 1,
        );
        this.meta(
          `bytes:${this.libraryId}`,
          Number(this.meta(`bytes:${this.libraryId}`) || 0) + e.size,
        );
      }
      return saved;
    };
    return this.transaction(run);
  }
  forgetFile(id) {
    this.q("DELETE FROM media WHERE library=? AND id=?").run(
      this.libraryId,
      id,
    );
  }
  invalidateFile(id) {
    this.q(
      "UPDATE media SET data=json_set(data,'$.integrityInvalid',1,'$.status','changed') WHERE library=? AND id=?",
    ).run(this.libraryId, id);
  }
  accountSavedUpload(u) {
    return this.transaction(() => {
      const added = this.q(
        "INSERT OR IGNORE INTO job_saves VALUES(?,?,?,?)",
      ).run(this.libraryId, u.jobId, u.uploadId, u.size).changes;
      const j = this.job(u.jobId);
      if (!j || !added) return j;
      return this.saveJob({
        ...j,
        savedFiles: (j.savedFiles || 0) + 1,
        savedBytes: (j.savedBytes || 0) + u.size,
        completedFiles: Math.max(
          j.completedFiles || 0,
          (j.savedFiles || 0) + 1,
        ),
        completedBytes: Math.max(
          j.completedBytes || 0,
          (j.savedBytes || 0) + u.size,
        ),
      });
    });
  }
  totals() {
    const m = this.q(
      "SELECT COUNT(*) AS mediaCount,COALESCE(SUM(size),0) AS mediaBytes FROM media WHERE library=?",
    ).get(this.libraryId);
    return {
      ...m,
      totalReceived: Number(this.meta(`count:${this.libraryId}`) || 0),
      totalBytes: Number(this.meta(`bytes:${this.libraryId}`) || 0),
    };
  }
  query(table, options = {}, onlyDevice) {
    if (!["media", "activity"].includes(table))
      throw new Error("Invalid collection");
    const where = ["library=?"];
    const args = [this.libraryId];
    const snapshot =
      bounded(options.snapshot, 0) ||
      this.q(
        `SELECT COALESCE(MAX(id),0) AS id FROM ${table} WHERE library=?`,
      ).get(this.libraryId).id;
    where.push("id<=?");
    args.push(snapshot);
    if (onlyDevice || options.deviceId) {
      where.push("device=?");
      args.push(onlyDevice || String(options.deviceId));
    }
    if (options.album) {
      where.push("album=?");
      args.push(String(options.album));
    }
    if (["photo", "video", "other"].includes(options.kind || options.type)) {
      where.push("kind=?");
      args.push(options.kind || options.type);
    }
    if (options.query) {
      where.push(
        "(name LIKE ? ESCAPE '\' OR album LIKE ? ESCAPE '\' OR device IN (SELECT id FROM devices WHERE name LIKE ? ESCAPE '\'))",
      );
      const term =
        "%" +
        String(options.query)
          .slice(0, 200)
          .replace(/[\\%_]/g, "\\$&") +
        "%";
      args.push(term, term, term);
    }
    for (const [field, op] of [
      ["dateFrom", ">="],
      ["dateTo", "<="],
    ]) {
      const raw = options[field];
      const dateOnly =
        typeof raw === "string" && /^\d{4}-\d{2}-\d{2}$/.test(raw);
      let value;
      if (dateOnly) {
        const [year, month, day] = raw.split("-").map(Number);
        value =
          field === "dateTo"
            ? new Date(year, month - 1, day + 1).getTime() - 1
            : new Date(year, month - 1, day).getTime();
      } else
        value =
          typeof raw === "string" && raw.includes("-")
            ? Date.parse(raw)
            : Number(raw);
      if (Number.isFinite(value) && value > 0) {
        where.push(`time${op}?`);
        args.push(value);
      }
    }
    const clause = where.join(" AND ");
    const stats = this.q(
      `SELECT COUNT(*) AS totalCount,COALESCE(SUM(size),0) AS totalBytes FROM ${table} WHERE ${clause}`,
    ).get(...args);
    const offset = bounded(options.offset, 0);
    const limit = Math.max(1, bounded(options.limit, 100, 500));
    const order =
      {
        newest: "time DESC,id DESC",
        oldest: "time ASC,id ASC",
        largest: "size DESC,id DESC",
        name: "name COLLATE NOCASE,id DESC",
      }[options.sort] || "time DESC,id DESC";
    const items = this.q(
      `SELECT * FROM ${table} WHERE ${clause} ORDER BY ${order} LIMIT ? OFFSET ?`,
    )
      .all(...args, limit, offset)
      .map((r) => this.decorate(r, table === "activity"));
    const albums = this.q(
      `SELECT DISTINCT album FROM ${table} WHERE library=?${onlyDevice ? " AND device=?" : ""} ORDER BY album COLLATE NOCASE`,
    )
      .all(...(onlyDevice ? [this.libraryId, onlyDevice] : [this.libraryId]))
      .map((r) => r.album);
    return {
      ...stats,
      items,
      snapshot,
      offset,
      limit,
      nextOffset: offset + items.length,
      hasMore: offset + items.length < stats.totalCount,
      returnedCount: items.length,
      albums,
      devices: this.devices().filter(
        (d) => !onlyDevice || d.deviceId === onlyDevice,
      ),
      historyCap: table === "activity" ? HISTORY_CAP : null,
      libraryId: this.libraryId,
      ...(table === "activity"
        ? { totalReceived: this.totals().totalReceived }
        : {}),
    };
  }
  upload(id) {
    return json(
      this.q("SELECT data FROM uploads WHERE id=? AND library=?").get(
        id,
        this.libraryId,
      ),
    );
  }
  uploads() {
    return this.q("SELECT data FROM uploads WHERE library=?")
      .all(this.libraryId)
      .map(json);
  }
  pendingUploads() {
    return this.q(
      "SELECT data FROM uploads WHERE library=? AND json_extract(data,'$.complete')=0",
    )
      .all(this.libraryId)
      .map(json);
  }
  saveUpload(u) {
    this.q(
      "INSERT INTO uploads VALUES(?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET updated=excluded.updated,data=excluded.data",
    ).run(
      u.uploadId,
      this.libraryId,
      u.deviceId,
      u.jobId,
      Date.now(),
      JSON.stringify(u),
    );
  }
  removeUpload(id) {
    this.q("DELETE FROM uploads WHERE id=? AND library=?").run(
      id,
      this.libraryId,
    );
  }
  job(id) {
    return json(
      this.q("SELECT data FROM jobs WHERE id=? AND library=?").get(
        id,
        this.libraryId,
      ),
    );
  }
  jobs(device) {
    return this.q(
      `SELECT data FROM jobs WHERE library=?${device ? " AND device=?" : ""} ORDER BY updated DESC LIMIT 100`,
    )
      .all(...(device ? [this.libraryId, device] : [this.libraryId]))
      .map(json)
      .map((j) => ({
        ...j,
        deviceName: this.device(j.deviceId)?.name || j.deviceName,
      }));
  }
  saveJob(j) {
    const job = {
      ...j,
      id: j.id || j.jobId,
      jobId: j.id || j.jobId,
      updatedAt: Date.now(),
    };
    this.q(
      "INSERT INTO jobs VALUES(?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET updated=excluded.updated,data=excluded.data",
    ).run(
      job.id,
      this.libraryId,
      job.deviceId,
      Date.now(),
      JSON.stringify(job),
    );
    return job;
  }
  clearHistory() {
    this.q("DELETE FROM activity WHERE library=?").run(this.libraryId);
    this.activityCount = 0;
  }
  exportHistory() {
    return {
      version: 3,
      libraryId: this.libraryId,
      ...this.totals(),
      activityLog: this.q("SELECT * FROM activity WHERE library=? ORDER BY id")
        .all(this.libraryId)
        .map((r) => this.decorate(r, true)),
      completedFiles: this.q("SELECT * FROM media WHERE library=? ORDER BY id")
        .all(this.libraryId)
        .map((r) => this.decorate(r)),
    };
  }
  importLegacy(data) {
    const entries = [
      ...(data.completedFiles || []),
      ...(data.activityLog || []),
    ];
    let imported = 0;
    for (const old of entries) {
      try {
        const relative =
          old.relativePath ||
          path.join(component(old.bucketName), component(old.fileName));
        const stat = fs.statSync(inside(this.root, relative));
        if (!stat.isFile() || (old.size && old.size !== stat.size)) continue;
        if (
          this.q("SELECT id FROM media WHERE library=? AND relative=?").get(
            this.libraryId,
            relative,
          )
        )
          continue;
        // Imports never confer ownership/deletion rights or restore credentials.
        this.saveMedia(
          {
            deviceId: "legacy",
            relativePath: relative,
            fileName: path.basename(relative),
            bucketName: old.bucketName || "Unsorted",
            size: stat.size,
            time: old.time || old.timestamp,
            hash: old.hash || old.md5 || "",
            hashAlgorithm: old.hashAlgorithm || "md5",
          },
          { arrival: true },
        );
        imported++;
      } catch {
        /* Missing legacy files remain absent; no invented receipts. */
      }
    }
    return imported;
  }
}

module.exports = {
  LibraryStore,
  HISTORY_CAP,
  component,
  fileComponent,
  inside,
  kind,
  bounded,
};
