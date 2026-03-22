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
/** @type {{ fileName: string, bucketName: string, size: number, time: number, status: string }[]} */
const activityLog = [];
/** @type {{ size: number, time: number }[]} */
const recentByteEvents = [];

function createServer(downloadPath) {
  const app = express();

  app.use(
    cors({
      origin: (origin, cb) => cb(null, true),
      methods: ["GET", "POST"],
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
      uptimeMs,
      currentSpeedBytesPerSec,
      averageSpeedBytesPerSec,
      downloadPath,
      recentActivity: activityLog.slice(-50).reverse(),
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

    const { md5Hash, bucketName, fileName } = req.body;
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

    broadcast("file-received", entry);

    // Notify Electron main process if available
    if (process.send) {
      process.send({ type: "file-received", data: entry });
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
