const express = require("express");
const multer = require("multer");
const cors = require("cors");
const path = require("path");
const fs = require("fs");
const crypto = require("crypto");
const os = require("os");

let totalReceived = 0;
let totalBytes = 0;
const startTime = Date.now();
const MAX_HISTORY_ENTRIES = 5000;
const DEFAULT_HISTORY_PAGE_SIZE = 100;
const MAX_HISTORY_PAGE_SIZE = 500;
/** @type {{ fileName: string, bucketName: string, size: number, time: number, status: string, md5?: string }[]} */
const activityLog = [];
/** @type {{ size: number, time: number }[]} */
const recentByteEvents = [];
/** Legacy hash-only index, kept so old history files still dedupe when no file metadata exists. */
const knownMd5s = new Set();
/** @type {Map<string, { fileName: string, bucketName: string, size: number, time: number, status: string, md5: string }[]>} */
const knownFilesByMd5 = new Map();
let historyStatePath = null;

/** Live progress for the (long-running, background) disk rebuild. Polled by the UI. */
let rebuildProgress = {
  running: false,
  total: 0,
  indexed: 0,
  bytes: 0,
  done: false,
  error: null,
  startedAt: 0,
  finishedAt: 0,
};

/** Hash a file by streaming it, so we never hold a whole (multi-GB) file in memory. */
function hashFileStreaming(filePath) {
  return new Promise((resolve, reject) => {
    const hash = crypto.createHash("md5");
    const stream = fs.createReadStream(filePath);
    stream.on("error", reject);
    stream.on("data", (chunk) => hash.update(chunk));
    stream.on("end", () => resolve(hash.digest("hex")));
  });
}

function listFilesRecursive(dir) {
  const results = [];
  const stack = [dir];
  while (stack.length > 0) {
    const current = stack.pop();
    let entries = [];
    try {
      entries = fs.readdirSync(current, { withFileTypes: true });
    } catch {
      continue;
    }
    for (const entry of entries) {
      const fullPath = path.join(current, entry.name);
      if (entry.isDirectory()) {
        stack.push(fullPath);
      } else if (entry.isFile() && !entry.name.endsWith(".part")) {
        results.push(fullPath);
      }
    }
  }
  return results;
}

async function rebuildIndexFromDisk(downloadPath) {
  knownMd5s.clear();
  knownFilesByMd5.clear();

  const files = listFilesRecursive(downloadPath);
  rebuildProgress.total = files.length;
  let indexed = 0;
  let bytes = 0;
  for (const filePath of files) {
    const stat = fs.statSync(filePath);
    const md5 = await hashFileStreaming(filePath);
    const relative = path.relative(downloadPath, path.dirname(filePath));
    const bucketName = relative && relative !== "." ? relative.replace(/\\/g, "/") : "Unsorted";
    addKnownFile({
      fileName: path.basename(filePath),
      bucketName,
      size: stat.size,
      time: stat.mtimeMs || Date.now(),
      status: "saved",
      md5,
    });
    indexed++;
    bytes += stat.size;
    rebuildProgress.indexed = indexed;
    rebuildProgress.bytes = bytes;
  }

  saveHistoryState();
  return { indexed, bytes };
}

/**
 * Kick off a disk rebuild in the background. Returns immediately so the HTTP request
 * never blocks for the (potentially many-minute) hashing pass. Progress is tracked in
 * `rebuildProgress` and polled via GET /history/rebuild-progress.
 */
function startRebuild(downloadPath) {
  if (rebuildProgress.running) return false;
  rebuildProgress = {
    running: true,
    total: 0,
    indexed: 0,
    bytes: 0,
    done: false,
    error: null,
    startedAt: Date.now(),
    finishedAt: 0,
  };
  rebuildIndexFromDisk(downloadPath)
    .then((result) => {
      rebuildProgress.indexed = result.indexed;
      rebuildProgress.bytes = result.bytes;
    })
    .catch((err) => {
      rebuildProgress.error = String((err && err.message) || err);
    })
    .finally(() => {
      rebuildProgress.running = false;
      rebuildProgress.done = true;
      rebuildProgress.finishedAt = Date.now();
    });
  return true;
}

/** Resolve a non-colliding final name in destDir (appends " (n)" like before). */
function resolveUniqueName(destDir, desiredName) {
  const ext = path.extname(desiredName);
  const base = path.basename(desiredName, ext);
  let name = desiredName;
  let counter = 1;
  while (fs.existsSync(path.join(destDir, name))) {
    name = `${base} (${counter})${ext}`;
    counter++;
  }
  return name;
}

function safeUnlink(filePath) {
  try {
    if (filePath && fs.existsSync(filePath)) fs.unlinkSync(filePath);
  } catch {
    // best effort
  }
}

function normalizeMd5(value) {
  return String(value || "").toLowerCase().replace(/[^a-f0-9]/g, "");
}

function sanitizeDeviceName(value) {
  // Keep it human-readable; drop control chars and HTML-significant characters,
  // but preserve spaces, digits and hyphens (e.g. "Pixel 7", "Galaxy-S23").
  const banned = new Set(["<", ">", '"', "'", "`"]);
  return String(value || "")
    .split("")
    .filter((ch) => {
      const code = ch.charCodeAt(0);
      return code >= 0x20 && code !== 0x7f && !banned.has(ch);
    })
    .join("")
    .trim()
    .slice(0, 60);
}

function normalizeHistoryEntry(item) {
  const md5 = normalizeMd5(item?.md5);
  const deviceName = sanitizeDeviceName(item?.deviceName);
  return {
    fileName: String(item?.fileName || ""),
    bucketName: String(item?.bucketName || "Unsorted"),
    size: Number(item?.size || 0),
    time: Number(item?.time || Date.now()),
    status: String(item?.status || "saved"),
    md5,
    ...(deviceName ? { deviceName } : {}),
  };
}

function addKnownFile(entry) {
  if (!entry?.md5 || !entry.fileName) return;
  knownMd5s.add(entry.md5);
  const existing = knownFilesByMd5.get(entry.md5) || [];
  const alreadyIndexed = existing.some(
    (item) => item.fileName === entry.fileName && item.bucketName === entry.bucketName
  );
  if (!alreadyIndexed) {
    existing.push(entry);
    knownFilesByMd5.set(entry.md5, existing);
  }
}

function allKnownFileEntries() {
  return [...knownFilesByMd5.values()].flat();
}

function filePathForEntry(downloadPath, entry) {
  return path.join(downloadPath, sanitizePath(entry.bucketName || "Unsorted"), path.basename(entry.fileName || ""));
}

function isEntryOnDisk(downloadPath, entry) {
  try {
    const filePath = filePathForEntry(downloadPath, entry);
    if (!fs.existsSync(filePath)) return false;
    const stat = fs.statSync(filePath);
    return stat.isFile() && (!entry.size || stat.size === entry.size);
  } catch {
    return false;
  }
}

function loadHistoryState() {
  totalReceived = 0;
  totalBytes = 0;
  activityLog.length = 0;
  knownMd5s.clear();
  knownFilesByMd5.clear();
  if (!historyStatePath || !fs.existsSync(historyStatePath)) return;
  try {
    const raw = fs.readFileSync(historyStatePath, "utf8");
    const parsed = JSON.parse(raw);
    totalReceived = Number(parsed.totalReceived || 0);
    totalBytes = Number(parsed.totalBytes || 0);
    const items = Array.isArray(parsed.activityLog)
      ? parsed.activityLog.slice(-MAX_HISTORY_ENTRIES)
      : [];
    for (const item of items) {
      activityLog.push(normalizeHistoryEntry(item));
    }

    // Dedup metadata is persisted separately from the capped activity log so it survives
    // beyond MAX_HISTORY_ENTRIES. Older history files may only have completedMd5s; those
    // remain hash-only and cannot be checked against disk until new metadata is imported.
    const persistedFiles = Array.isArray(parsed.completedFiles) ? parsed.completedFiles : [];
    for (const item of persistedFiles) {
      addKnownFile(normalizeHistoryEntry(item));
    }
    const persistedHashes = Array.isArray(parsed.completedMd5s) ? parsed.completedMd5s : [];
    for (const h of persistedHashes) {
      const md5 = normalizeMd5(h);
      if (md5) knownMd5s.add(md5);
    }
    for (const item of activityLog) {
      addKnownFile(item);
    }
  } catch {
    totalReceived = 0;
    totalBytes = 0;
    activityLog.length = 0;
    knownMd5s.clear();
    knownFilesByMd5.clear();
  }
}

function saveHistoryState() {
  if (!historyStatePath) return;
  try {
    const dir = path.dirname(historyStatePath);
    fs.mkdirSync(dir, { recursive: true });
    const tmp = `${historyStatePath}.tmp`;
    fs.writeFileSync(
      tmp,
      JSON.stringify(
        {
          version: 2,
          totalReceived,
          totalBytes,
          activityLog,
          completedFiles: allKnownFileEntries(),
          completedMd5s: [...knownMd5s],
        },
        null,
        2
      ),
      "utf8"
    );
    fs.renameSync(tmp, historyStatePath);
  } catch {
    // Best effort persistence; ignore write failures.
  }
}

function parsePositiveTimestampMs(value) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed <= 0) return 0;
  return Math.floor(parsed);
}

function parseBoundedInt(value, fallback, min, max) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return fallback;
  const intValue = Math.floor(parsed);
  if (intValue < min) return min;
  if (intValue > max) return max;
  return intValue;
}

function buildHistoryPage(offset, limit) {
  const totalCount = activityLog.length;
  const safeOffset = parseBoundedInt(offset, 0, 0, totalCount);
  const safeLimit = parseBoundedInt(limit, DEFAULT_HISTORY_PAGE_SIZE, 1, MAX_HISTORY_PAGE_SIZE);

  const endExclusive = totalCount - safeOffset;
  const startInclusive = Math.max(endExclusive - safeLimit, 0);
  const items = activityLog.slice(startInclusive, endExclusive).reverse();

  return {
    items,
    totalCount,
    offset: safeOffset,
    limit: safeLimit,
    returnedCount: items.length,
    nextOffset: safeOffset + items.length,
    hasMore: startInclusive > 0,
  };
}

function findKnownMd5Entry(downloadPath, md5) {
  const indexed = knownFilesByMd5.get(md5) || [];
  for (let i = indexed.length - 1; i >= 0; i--) {
    if (isEntryOnDisk(downloadPath, indexed[i])) return indexed[i];
  }
  if (indexed.length > 0) {
    knownFilesByMd5.delete(md5);
    knownMd5s.delete(md5);
    saveHistoryState();
    return null;
  }

  // Legacy fallback: old history-state files stored only hashes for entries outside the
  // capped activity log. They can still dedupe, but cannot prove the file is still on disk.
  return knownMd5s.has(md5) ? { md5, legacyOnly: true } : null;
}

/**
 * Group on-disk files that share an MD5. Reads from the in-memory index (built by a rebuild
 * or normal uploads), so run a rebuild first for an accurate picture of the current folder.
 */
function findDuplicateGroups(downloadPath) {
  const groups = [];
  for (const [md5, entries] of knownFilesByMd5) {
    const onDisk = entries.filter((e) => isEntryOnDisk(downloadPath, e));
    if (onDisk.length > 1) groups.push({ md5, entries: onDisk });
  }
  return groups;
}

/**
 * Find duplicates and (unless dryRun) delete the extras, keeping the oldest copy of each.
 * Matches the dedup design: one stored file per MD5.
 */
function removeDuplicateFiles(downloadPath, { dryRun = false } = {}) {
  const groups = findDuplicateGroups(downloadPath);
  let removed = 0;
  let bytesFreed = 0;

  for (const group of groups) {
    const sorted = [...group.entries].sort((a, b) => (a.time || 0) - (b.time || 0));
    const extras = sorted.slice(1); // keep sorted[0] (oldest)
    for (const entry of extras) {
      const filePath = filePathForEntry(downloadPath, entry);
      let size = entry.size || 0;
      try {
        size = fs.statSync(filePath).size;
      } catch {
        // fall back to the recorded size
      }
      if (dryRun) {
        removed++;
        bytesFreed += size;
        continue;
      }
      try {
        fs.unlinkSync(filePath);
      } catch {
        continue; // couldn't delete — leave the index entry alone
      }
      removed++;
      bytesFreed += size;
      const list = (knownFilesByMd5.get(group.md5) || []).filter(
        (e) => !(e.fileName === entry.fileName && e.bucketName === entry.bucketName)
      );
      if (list.length > 0) {
        knownFilesByMd5.set(group.md5, list);
      } else {
        knownFilesByMd5.delete(group.md5);
        knownMd5s.delete(group.md5);
      }
    }
  }

  if (!dryRun && removed > 0) saveHistoryState();
  return { groups: groups.length, removed, bytesFreed };
}

/** Remove the index entry for a given md5/file pair, dropping the map key when its list empties. */
function forgetKnownFile(md5, entry) {
  const list = (knownFilesByMd5.get(md5) || []).filter(
    (e) => !(e.fileName === entry.fileName && e.bucketName === entry.bucketName)
  );
  if (list.length > 0) {
    knownFilesByMd5.set(md5, list);
  } else {
    knownFilesByMd5.delete(md5);
    knownMd5s.delete(md5);
  }
}

/**
 * Delete files the phone reports as removed (Sync mode). Conservative by design: a file is only
 * deleted when its md5 is in our index AND still on disk — content the server never received
 * (no md5 match) is reported in `notFound` and left untouched. Matching is by md5 (the dedup key);
 * the bucketName/fileName hints only disambiguate when one md5 has several indexed copies.
 */
function deleteSyncedFiles(downloadPath, files) {
  let deleted = 0;
  let bytesFreed = 0;
  const deletedMd5s = [];
  const notFound = [];

  for (const file of files || []) {
    const md5 = normalizeMd5(file?.md5);
    const indexed = (md5 && knownFilesByMd5.get(md5)) || [];
    const onDisk = indexed.filter((e) => isEntryOnDisk(downloadPath, e));
    if (onDisk.length === 0) {
      notFound.push(md5);
      continue;
    }

    // Prefer the copy the phone points at; otherwise just take the first on-disk copy.
    const hintBucket = file?.bucketName ? sanitizePath(file.bucketName) : null;
    const hintName = file?.fileName ? path.basename(String(file.fileName)) : null;
    const entry =
      onDisk.find((e) => (!hintBucket || e.bucketName === hintBucket) && (!hintName || e.fileName === hintName)) ||
      onDisk.find((e) => !hintBucket || e.bucketName === hintBucket) ||
      onDisk[0];

    const filePath = filePathForEntry(downloadPath, entry);
    let size = entry.size || 0;
    try {
      size = fs.statSync(filePath).size;
    } catch {
      // fall back to the recorded size
    }
    safeUnlink(filePath);

    forgetKnownFile(md5, entry);
    const logIdx = activityLog.findIndex(
      (e) => e.md5 === md5 && e.fileName === entry.fileName && e.bucketName === entry.bucketName
    );
    if (logIdx >= 0) activityLog.splice(logIdx, 1);

    deleted++;
    bytesFreed += size;
    deletedMd5s.push(md5);
  }

  if (deleted > 0) saveHistoryState();
  return { deleted, bytesFreed, deletedMd5s, notFound };
}

function createServer(downloadPath, options = {}) {
  historyStatePath = options.historyStatePath || null;
  const onFileReceived = typeof options.onFileReceived === "function" ? options.onFileReceived : null;
  // Pairing token: when set, gates the destructive endpoints (deleting/clearing) so a random
  // device on the LAN can't wipe the user's files. Empty token = open (back-compat).
  const pairingToken = String(options.pairingToken || "").trim();
  loadHistoryState();
  saveHistoryState();

  const app = express();

  app.use(
    cors({
      origin: (origin, cb) => cb(null, true),
      methods: ["GET", "POST", "DELETE"],
      allowedHeaders: ["Content-Type", "X-Pherry-Token", "X-Device-Name"],
    })
  );
  app.use(express.json());

  // Require the pairing token on destructive routes. Sending files stays open so QR-less /
  // discovery-based pairing can still back up photos; only operations that remove data from the
  // PC need the token (which the phone gets by scanning the desktop QR).
  function requireToken(req, res, next) {
    if (!pairingToken) return next();
    const provided = String(req.headers["x-pherry-token"] || "").trim();
    if (provided && provided === pairingToken) return next();
    return res.status(401).json({
      success: false,
      error: "Pairing required. Scan the desktop QR code to allow removing files.",
    });
  }

  // Health check
  app.get("/health", (_req, res) => {
    res.json({ status: "ok", serverName: os.hostname() });
  });

  // Server status / stats
  app.get("/status", (_req, res) => {
    const now = Date.now();
    const windowMs = 5000;
    const cutoff = now - windowMs;

    while (recentByteEvents.length > 0 && recentByteEvents[0].time < cutoff) {
      recentByteEvents.shift();
    }

    const recentBytes = recentByteEvents.reduce((sum, e) => sum + e.size, 0);
    const currentSpeedBytesPerSec = Math.round((recentBytes * 1000) / windowMs);
    const uptimeMs = Date.now() - startTime;
    const averageSpeedBytesPerSec = uptimeMs > 0 ? Math.round((totalBytes * 1000) / uptimeMs) : 0;

    res.json({
      totalReceived,
      totalBytes,
      historyCount: activityLog.length,
      lastTransferAt: activityLog[activityLog.length - 1]?.time || 0,
      uptimeMs,
      currentSpeedBytesPerSec,
      averageSpeedBytesPerSec,
      downloadPath,
      recentActivity: activityLog.slice(-50).reverse(),
    });
  });

  app.get("/history", (_req, res) => {
    const page = buildHistoryPage(_req.query.offset, _req.query.limit);
    res.json({
      totalReceived,
      totalBytes,
      historyCount: activityLog.length,
      lastTransferAt: activityLog[activityLog.length - 1]?.time || 0,
      totalCount: page.totalCount,
      offset: page.offset,
      limit: page.limit,
      returnedCount: page.returnedCount,
      nextOffset: page.nextOffset,
      hasMore: page.hasMore,
      items: page.items,
    });
  });

  app.post("/history/clear", requireToken, (_req, res) => {
    totalReceived = 0;
    totalBytes = 0;
    activityLog.length = 0;
    recentByteEvents.length = 0;
    knownMd5s.clear();
    knownFilesByMd5.clear();
    saveHistoryState();
    res.json({ success: true });
  });

  app.post("/history/rebuild-index", (_req, res) => {
    const started = startRebuild(downloadPath);
    res.json({ success: true, started, alreadyRunning: !started });
  });

  app.get("/history/rebuild-progress", (_req, res) => {
    res.json({ ...rebuildProgress });
  });

  app.post("/history/remove-duplicates", requireToken, (req, res) => {
    try {
      const dryRun = !!(req.body && req.body.dryRun);
      const result = removeDuplicateFiles(downloadPath, { dryRun });
      res.json({ success: true, dryRun, ...result });
    } catch (err) {
      res.status(500).json({ success: false, error: String((err && err.message) || err) });
    }
  });

  // Sync mode: delete files the phone has removed. Matches by md5 (the dedup key) and only
  // touches content the server actually holds, so manually-added files are never deleted.
  app.post("/sync/delete", requireToken, (req, res) => {
    try {
      const files = Array.isArray(req.body && req.body.files) ? req.body.files : [];
      const result = deleteSyncedFiles(downloadPath, files);
      res.json({ success: true, ...result });
    } catch (err) {
      res.status(500).json({ success: false, error: String((err && err.message) || err) });
    }
  });

  // Dedup pre-check: lets the client skip re-uploading a file the server already has.
  app.get("/exists", (req, res) => {
    const md5 = normalizeMd5(req.query.md5);
    if (!md5) {
      return res.status(400).json({ error: "md5 query param required" });
    }
    const match = findKnownMd5Entry(downloadPath, md5);
    const exists = !!match;
    res.json({
      exists,
      match: match && !match.legacyOnly
        ? {
            fileName: match.fileName,
            bucketName: match.bucketName,
            size: match.size,
            time: match.time,
          }
        : null,
    });
  });

  // Activity log stream (SSE)
  const sseClients = new Set();
  app.get("/events", (req, res) => {
    res.writeHead(200, {
      "Content-Type": "text/event-stream",
      "Cache-Control": "no-cache",
      Connection: "keep-alive",
    });
    sseClients.add(res);
    req.on("close", () => sseClients.delete(res));
  });

  function broadcast(event, data) {
    const msg = `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
    for (const client of sseClients) {
      client.write(msg);
    }
  }

  // Multer storage — saves into bucketName subfolder
  const storage = multer.diskStorage({
    destination: (req, _file, cb) => {
      const bucket = sanitizePath(req.body.bucketName || "Unsorted");
      const dest = path.join(downloadPath, bucket);
      fs.mkdirSync(dest, { recursive: true });
      cb(null, dest);
    },
    filename: (_req, _file, cb) => {
      // Write to a hidden temp file first; the handler verifies the hash and only then
      // atomically renames it to the final name. This keeps truncated files (from a dropped
      // connection) from ever appearing under a real name in the destination folder.
      const md5 = normalizeMd5(_req.body.md5Hash);
      const token = md5 || `${Date.now()}-${Math.random().toString(36).slice(2)}`;
      cb(null, `.${token}.part`);
    },
  });

  const upload = multer({
    storage,
    limits: { fileSize: 16 * 1024 * 1024 * 1024 }, // 16 GB max
  });

  // File upload endpoint
  app.post("/upload", upload.single("file"), async (req, res) => {
    if (!req.file) {
      return res.status(400).json({ success: false, error: "No file provided" });
    }

    const { md5Hash, bucketName, sourceTimestampMs } = req.body;
    const tempPath = req.file.path;
    const destDir = path.dirname(tempPath);

    try {
      // Verify integrity before committing the file. Streamed so a multi-GB upload doesn't
      // get buffered whole in memory.
      if (md5Hash) {
        const hash = await hashFileStreaming(tempPath);
        if (hash !== normalizeMd5(md5Hash)) {
          safeUnlink(tempPath);
          return res
            .status(422)
            .json({ success: false, error: "MD5 mismatch", expected: md5Hash, got: hash });
        }
      }

      // Claim the final (deduped) name only now that the bytes are verified, then commit.
      const finalName = resolveUniqueName(destDir, req.file.originalname);
      const finalPath = path.join(destDir, finalName);
      fs.renameSync(tempPath, finalPath);

      const fileSize = req.file.size;

      const sourceTimeMs = parsePositiveTimestampMs(sourceTimestampMs);
      if (sourceTimeMs > 0) {
        try {
          const originalDate = new Date(sourceTimeMs);
          fs.utimesSync(finalPath, originalDate, originalDate);
        } catch {
          // Some filesystems may reject specific timestamp updates.
        }
      }

      const md5 = normalizeMd5(md5Hash);
      const savedBucket = sanitizePath(bucketName || "Unsorted");
      const deviceName = sanitizeDeviceName(req.headers["x-device-name"]);

      totalReceived++;
      totalBytes += fileSize;
      recentByteEvents.push({ size: fileSize, time: Date.now() });

      const entry = {
        fileName: finalName,
        bucketName: savedBucket,
        size: fileSize,
        time: Date.now(),
        status: "saved",
        md5,
        ...(deviceName ? { deviceName } : {}),
      };
      addKnownFile(entry);
      activityLog.push(entry);
      while (activityLog.length > MAX_HISTORY_ENTRIES) {
        activityLog.shift();
      }
      saveHistoryState();

      broadcast("file-received", entry);

      if (onFileReceived) {
        try {
          onFileReceived(entry);
        } catch {
          // never let listener errors break the upload response
        }
      }

      res.json({
        success: true,
        fileName: finalName,
        path: finalPath,
        size: fileSize,
      });
    } catch (err) {
      safeUnlink(tempPath);
      res
        .status(500)
        .json({ success: false, error: String((err && err.message) || err) });
    }
  });

  return app;
}

/** Strip path traversal characters for safety */
function sanitizePath(input) {
  return String(input)
    .replace(/\.\./g, "")
    .replace(/[<>:"|?*]/g, "")
    .replace(/^[\\/]+/, "")
    .trim()
    || "Unsorted";
}

function getLocalIPs() {
  const interfaces = os.networkInterfaces();
  const ips = [];
  for (const iface of Object.values(interfaces)) {
    for (const info of iface) {
      if (info.family === "IPv4" && !info.internal) {
        ips.push(info.address);
      }
    }
  }
  return ips;
}

module.exports = { createServer, getLocalIPs };
