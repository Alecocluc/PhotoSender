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
/** Content hashes of every file the server has stored — used for cross-session dedup. */
const knownMd5s = new Set();
let historyStatePath = null;

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

function loadHistoryState() {
  if (!historyStatePath || !fs.existsSync(historyStatePath)) return;
  try {
    const raw = fs.readFileSync(historyStatePath, "utf8");
    const parsed = JSON.parse(raw);
    totalReceived = Number(parsed.totalReceived || 0);
    totalBytes = Number(parsed.totalBytes || 0);
    activityLog.length = 0;
    const items = Array.isArray(parsed.activityLog)
      ? parsed.activityLog.slice(-MAX_HISTORY_ENTRIES)
      : [];
    for (const item of items) {
      const md5 = normalizeMd5(item.md5);
      activityLog.push({
        fileName: String(item.fileName || ""),
        bucketName: String(item.bucketName || "Unsorted"),
        size: Number(item.size || 0),
        time: Number(item.time || Date.now()),
        status: String(item.status || "saved"),
        md5,
      });
    }

    // Dedup index is persisted separately from the (capped) activity log so it survives
    // beyond MAX_HISTORY_ENTRIES; backfill from any entries that carried a hash.
    knownMd5s.clear();
    const persistedHashes = Array.isArray(parsed.completedMd5s) ? parsed.completedMd5s : [];
    for (const h of persistedHashes) {
      const md5 = normalizeMd5(h);
      if (md5) knownMd5s.add(md5);
    }
    for (const item of activityLog) {
      if (item.md5) knownMd5s.add(item.md5);
    }
  } catch {
    totalReceived = 0;
    totalBytes = 0;
    activityLog.length = 0;
    knownMd5s.clear();
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
        { totalReceived, totalBytes, activityLog, completedMd5s: [...knownMd5s] },
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

function findKnownMd5Entry(md5) {
  for (let i = activityLog.length - 1; i >= 0; i--) {
    if (activityLog[i].md5 === md5) return activityLog[i];
  }
  return null;
}

function createServer(downloadPath, options = {}) {
  historyStatePath = options.historyStatePath || null;
  const onFileReceived = typeof options.onFileReceived === "function" ? options.onFileReceived : null;
  loadHistoryState();

  const app = express();

  app.use(
    cors({
      origin: (origin, cb) => cb(null, true),
      methods: ["GET", "POST", "DELETE"],
    })
  );
  app.use(express.json());

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

  app.post("/history/clear", (_req, res) => {
    totalReceived = 0;
    totalBytes = 0;
    activityLog.length = 0;
    recentByteEvents.length = 0;
    knownMd5s.clear();
    saveHistoryState();
    res.json({ success: true });
  });

  // Dedup pre-check: lets the client skip re-uploading a file the server already has.
  app.get("/exists", (req, res) => {
    const md5 = normalizeMd5(req.query.md5);
    if (!md5) {
      return res.status(400).json({ error: "md5 query param required" });
    }
    const exists = knownMd5s.has(md5);
    const match = exists ? findKnownMd5Entry(md5) : null;
    res.json({
      exists,
      match: match
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
      if (md5) knownMd5s.add(md5);

      totalReceived++;
      totalBytes += fileSize;
      recentByteEvents.push({ size: fileSize, time: Date.now() });

      const entry = {
        fileName: finalName,
        bucketName: bucketName || "Unsorted",
        size: fileSize,
        time: Date.now(),
        status: "saved",
        md5,
      };
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
