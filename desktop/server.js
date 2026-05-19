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
/** @type {{ fileName: string, bucketName: string, size: number, time: number, status: string }[]} */
const activityLog = [];
/** @type {{ size: number, time: number }[]} */
const recentByteEvents = [];
let historyStatePath = null;

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
      activityLog.push({
        fileName: String(item.fileName || ""),
        bucketName: String(item.bucketName || "Unsorted"),
        size: Number(item.size || 0),
        time: Number(item.time || Date.now()),
        status: String(item.status || "saved"),
      });
    }
  } catch {
    totalReceived = 0;
    totalBytes = 0;
    activityLog.length = 0;
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
      JSON.stringify({ totalReceived, totalBytes, activityLog }, null, 2),
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
    saveHistoryState();
    res.json({ success: true });
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
    filename: (_req, file, cb) => {
      // Preserve original name, dedup with suffix if exists
      let name = file.originalname;
      const dest = path.join(
        downloadPath,
        sanitizePath(_req.body.bucketName || "Unsorted")
      );
      const ext = path.extname(name);
      const base = path.basename(name, ext);
      let counter = 1;
      while (fs.existsSync(path.join(dest, name))) {
        name = `${base} (${counter})${ext}`;
        counter++;
      }
      cb(null, name);
    },
  });

  const upload = multer({
    storage,
    limits: { fileSize: 4 * 1024 * 1024 * 1024 }, // 4 GB max
  });

  // File upload endpoint
  app.post("/upload", upload.single("file"), (req, res) => {
    if (!req.file) {
      return res.status(400).json({ success: false, error: "No file provided" });
    }

    const { md5Hash, bucketName, fileName, sourceTimestampMs } = req.body;
    const filePath = req.file.path;
    const fileSize = req.file.size;

    // Verify MD5 if provided
    if (md5Hash) {
      const hash = crypto
        .createHash("md5")
        .update(fs.readFileSync(filePath))
        .digest("hex");
      if (hash !== md5Hash) {
        fs.unlinkSync(filePath);
        return res
          .status(422)
          .json({ success: false, error: "MD5 mismatch", expected: md5Hash, got: hash });
      }
    }

    const sourceTimeMs = parsePositiveTimestampMs(sourceTimestampMs);
    if (sourceTimeMs > 0) {
      try {
        const originalDate = new Date(sourceTimeMs);
        fs.utimesSync(filePath, originalDate, originalDate);
      } catch {
        // Some filesystems may reject specific timestamp updates.
      }
    }

    totalReceived++;
    totalBytes += fileSize;
    recentByteEvents.push({ size: fileSize, time: Date.now() });

    const entry = {
      fileName: req.file.filename,
      bucketName: bucketName || "Unsorted",
      size: fileSize,
      time: Date.now(),
      status: "saved",
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
      fileName: req.file.filename,
      path: filePath,
      size: fileSize,
    });
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
