const {
  app, BrowserWindow, ipcMain, dialog, shell,
  Tray, Menu, Notification, nativeImage, nativeTheme,
} = require("electron");
const path = require("path");
const fs = require("fs");
const crypto = require("crypto");
const os = require("os");
const { createServer, getLocalIPs } = require("./server");

let Bonjour = null;
try {
  // Optional dependency: enables zero-config (mDNS) discovery from the phone.
  Bonjour = require("bonjour-service").Bonjour;
} catch {
  Bonjour = null;
}

const DEFAULT_PORT = 3210;
const MIN_PORT = 1024;
const MAX_PORT = 65535;

let mainWindow;
let serverInstance;
let historyStatePath;
let settingsPath;
let tray = null;
let bonjourInstance = null;
let bonjourService = null;

// One Pherry per computer: a second launch would fail to bind the port and pretend to receive.
const gotSingleInstanceLock = app.requestSingleInstanceLock();
if (!gotSingleInstanceLock) {
  app.quit();
} else {
  app.on("second-instance", () => showMainWindow());
}

/** What the receiver is doing now. Kept so a window opened later (or reloaded) can ask for it. */
let lastServerState = { running: false, port: DEFAULT_PORT, error: null, code: null };

let settings = {
  downloadPath: "",
  port: DEFAULT_PORT,
  theme: "system", // 'system' | 'light' | 'dark'
  autoOpenFolder: false,
  launchAtStartup: false,
  minimizeToTray: true,
  notifyOnArrival: true,
  pairingToken: "",
  deviceId: "",
};

/** Short, human-typeable pairing token. Kept compact so it fits the QR encoder budget. */
function generatePairingToken() {
  const alphabet = "abcdefghijkmnpqrstuvwxyz23456789"; // no look-alikes (l, o, 0, 1)
  const bytes = crypto.randomBytes(6);
  let out = "";
  for (let i = 0; i < 6; i++) out += alphabet[bytes[i] % alphabet.length];
  return out;
}

/**
 * Stable, non-secret identifier for this desktop. Advertised over mDNS and returned from /health so
 * the phone can keep its pairing token bound to the *machine* rather than its current LAN address —
 * delete rights then survive the PC's IP changing (DHCP). Generated once and persisted.
 */
function generateDeviceId() {
  return crypto.randomBytes(16).toString("hex");
}

function loadSettings() {
  try {
    if (fs.existsSync(settingsPath)) {
      const raw = fs.readFileSync(settingsPath, "utf8");
      const parsed = JSON.parse(raw);
      if (parsed && typeof parsed === "object") {
        settings = { ...settings, ...parsed };
      }
    }
  } catch {
    // ignore — fall back to defaults
  }
  if (!settings.downloadPath || typeof settings.downloadPath !== "string") {
    settings.downloadPath = path.join(app.getPath("pictures"), "Pherry");
  }
  const port = Number(settings.port);
  if (!Number.isFinite(port) || port < MIN_PORT || port > MAX_PORT) {
    settings.port = DEFAULT_PORT;
  } else {
    settings.port = Math.floor(port);
  }
  if (!["system", "light", "dark"].includes(settings.theme)) {
    settings.theme = "system";
  }
  if (typeof settings.minimizeToTray !== "boolean") settings.minimizeToTray = true;
  if (typeof settings.notifyOnArrival !== "boolean") settings.notifyOnArrival = true;
  let needsSave = false;
  if (!settings.pairingToken || typeof settings.pairingToken !== "string") {
    settings.pairingToken = generatePairingToken();
    needsSave = true;
  }
  if (!settings.deviceId || typeof settings.deviceId !== "string") {
    settings.deviceId = generateDeviceId();
    needsSave = true;
  }
  if (needsSave) saveSettings();
}

function saveSettings() {
  try {
    fs.mkdirSync(path.dirname(settingsPath), { recursive: true });
    const tmp = `${settingsPath}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(settings, null, 2), "utf8");
    fs.renameSync(tmp, settingsPath);
  } catch {
    // best effort
  }
}

function applyLaunchAtStartup() {
  try {
    app.setLoginItemSettings({ openAtLogin: !!settings.launchAtStartup });
  } catch {
    // not supported on all platforms
  }
}

/** Paper by day, darkroom by night: matches the renderer's ground so the window never flashes. */
function windowBackground() {
  const dark = settings.theme === "dark" || (settings.theme === "system" && nativeTheme.shouldUseDarkColors);
  return dark ? "#161513" : "#F5F5F2";
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1280,
    height: 820,
    minWidth: 480,
    minHeight: 560,
    title: "Pherry",
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
    },
    icon: path.join(__dirname, "renderer", "pherry-icon.png"),
    show: false,
    backgroundColor: windowBackground(),
  });

  mainWindow.loadFile(path.join(__dirname, "renderer", "index.html"));
  // A bind failure at launch happens before any window exists; repeat the state once the page is up.
  mainWindow.webContents.on("did-finish-load", () => broadcastToRenderer("server-state", { ...lastServerState }));

  mainWindow.once("ready-to-show", () => mainWindow.show());

  // Closing the window hides it to the tray (the receiver keeps running) unless the user
  // explicitly quit or disabled the tray behavior.
  mainWindow.on("close", (event) => {
    if (!app.isQuitting && settings.minimizeToTray && tray) {
      event.preventDefault();
      mainWindow.hide();
    }
  });
  mainWindow.on("closed", () => {
    mainWindow = null;
  });
}

function broadcastToRenderer(channel, payload) {
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send(channel, payload);
  }
}

function setServerState(next) {
  lastServerState = { running: false, port: settings.port, error: null, code: null, ...next };
  updateTrayTooltip();
  broadcastToRenderer("server-state", { ...lastServerState });
}

/** Same order as the renderer's sortedIPs(): home Wi-Fi ranges first, VPN and virtual adapters last. */
function sortedLocalIPs() {
  const score = (ip) => (/^192\.168\./.test(ip) ? 0 : /^10\./.test(ip) ? 1 : /^172\./.test(ip) ? 2 : 3);
  return [...(getLocalIPs() || [])].sort((a, b) => score(a) - score(b));
}

function formatBytes(bytes) {
  if (!bytes || bytes <= 0) return "0 B";
  const units = ["B", "KB", "MB", "GB", "TB"];
  let i = 0;
  let v = bytes;
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
  return `${v.toFixed(v >= 10 || i === 0 ? 0 : 1)} ${units[i]}`;
}

// ── mDNS / DNS-SD advertising ────────────────────────────────────────────────
// Publishes "_pherry._tcp" so the phone can discover this receiver with no QR/typing,
// and survive the PC's IP changing on DHCP renewal.
function stopBonjour() {
  try {
    if (bonjourService) bonjourService.stop();
  } catch { /* ignore */ }
  bonjourService = null;
  try {
    if (bonjourInstance) bonjourInstance.destroy();
  } catch { /* ignore */ }
  bonjourInstance = null;
}

function startBonjour() {
  if (!Bonjour) return;
  stopBonjour();
  try {
    bonjourInstance = new Bonjour();
    bonjourService = bonjourInstance.publish({
      name: `Pherry on ${os.hostname()}`.slice(0, 63),
      type: "pherry",
      protocol: "tcp",
      port: settings.port,
      txt: { host: os.hostname(), v: "1", id: settings.deviceId },
    });
  } catch (err) {
    console.error("mDNS publish failed:", err.message);
  }
}

function stopServer() {
  return new Promise((resolve) => {
    stopBonjour();
    if (!serverInstance) return resolve();
    const ref = serverInstance;
    serverInstance = null;
    try {
      ref.close(() => resolve());
    } catch {
      resolve();
    }
  });
}

async function startServer() {
  await stopServer();
  fs.mkdirSync(settings.downloadPath, { recursive: true });
  const expressApp = createServer(settings.downloadPath, {
    historyStatePath,
    pairingToken: settings.pairingToken,
    deviceId: settings.deviceId,
    onFileReceived: (entry) => {
      broadcastToRenderer("file-received", entry);
      if (trackArrival(entry)) notifyArrival(entry);
      if (settings.autoOpenFolder) {
        const bucket = entry?.bucketName || "Unsorted";
        const target = path.join(settings.downloadPath, bucket);
        if (fs.existsSync(target)) shell.openPath(target);
      }
    },
    // A phone's Sync removed files here: the window's lists and totals need re-reading.
    onFilesRemoved: (info) => broadcastToRenderer("files-removed", info),
  });

  return new Promise((resolve, reject) => {
    const server = expressApp.listen(settings.port, "0.0.0.0", () => {
      serverInstance = server;
      console.log(`Pherry server listening on port ${settings.port}`);
      startBonjour();
      refreshTray();
      setServerState({ running: true, port: settings.port });
      resolve();
    });
    // Node 18+ cuts any request that takes longer than 5 minutes to arrive in full (requestTimeout
    // defaults to 300 s). A multi-GB video over Wi-Fi, sharing bandwidth with parallel uploads,
    // takes longer than that, so every big file was cut off and restarted from zero, forever.
    // Lift the whole-request limit; drop only sockets that go silent for 2 minutes instead.
    server.requestTimeout = 0;
    server.setTimeout(120000);
    server.once("error", (err) => {
      console.error("Server bind error:", err.message);
      setServerState({ running: false, port: settings.port, error: err.message, code: err.code || null });
      reject(err);
    });
  });
}

// ── Arrival notifications ──────────────────────────────────────────────────
// A backup is thousands of files: one toast (with sound) when a phone starts sending, then one
// silent summary once it has been quiet for ARRIVAL_GAP_MS. Mirrors SESSION_GAP_MS in renderer/state.js.
const ARRIVAL_GAP_MS = 20000;
let arrivalSession = null; // { device, count, bytes, bucket, timer }

function windowFocused() {
  return !!(mainWindow && !mainWindow.isDestroyed() && mainWindow.isFocused());
}

function showArrivalToast({ title, body, silent, bucket }) {
  const n = new Notification({ title, body, silent, icon: trayImage() || undefined });
  n.on("click", () => {
    showMainWindow();
    const target = path.join(settings.downloadPath, bucket || "Unsorted");
    if (fs.existsSync(target)) shell.openPath(target);
  });
  n.show();
}

/** Fold one arrival into the phone's session. Returns true for the first file of a new session. */
function trackArrival(entry) {
  const device = entry?.deviceName || "";
  let s = arrivalSession;
  let started = false;
  if (!s || s.device !== device) {
    if (s) {
      clearTimeout(s.timer);
      summarizeArrivals(s);
    }
    s = arrivalSession = { device, count: 0, bytes: 0, bucket: "", timer: null };
    started = true;
  }
  s.count += 1;
  s.bytes += Number(entry?.size) || 0;
  s.bucket = entry?.bucketName || s.bucket;
  clearTimeout(s.timer);
  s.timer = setTimeout(() => {
    if (arrivalSession === s) arrivalSession = null;
    summarizeArrivals(s);
  }, ARRIVAL_GAP_MS);
  return started;
}

/** The quiet summary for a session of more than one file (the first toast already named a single one). */
function summarizeArrivals(s) {
  try {
    if (s.count < 2 || !settings.notifyOnArrival || !Notification.isSupported() || windowFocused()) return;
    showArrivalToast({
      title: `${s.count.toLocaleString()} files arrived${s.device ? ` from ${s.device}` : ""}`,
      body: formatBytes(s.bytes),
      silent: true,
      bucket: s.bucket,
    });
  } catch { /* best effort */ }
}

/** Native OS notification when a phone starts sending (unless the window is focused). */
function notifyArrival(entry) {
  try {
    if (!settings.notifyOnArrival) return;
    if (!Notification.isSupported()) return;
    if (windowFocused()) return;
    showArrivalToast({
      title: entry?.deviceName ? `Receiving from ${entry.deviceName}` : "Receiving files",
      body: entry?.fileName || entry?.originalName || "",
      silent: false,
      bucket: entry?.bucketName,
    });
  } catch { /* best effort */ }
}

/** fetch() against our own server with the pairing token attached (for desktop-side actions). */
function localFetch(pathname, options = {}) {
  const headers = { ...(options.headers || {}), "X-Pherry-Token": settings.pairingToken };
  return fetch(`http://127.0.0.1:${settings.port}${pathname}`, { ...options, headers });
}

// ── IPC handlers ───────────────────────────────────────────────────────────

ipcMain.handle("get-status", async () => {
  try {
    const res = await fetch(`http://127.0.0.1:${settings.port}/status`);
    return await res.json();
  } catch {
    return { totalReceived: 0, totalBytes: 0, uptimeMs: 0, recentActivity: [] };
  }
});

ipcMain.handle("get-history", async (_e, options = {}) => {
  try {
    const limit = Number(options.limit || 0);
    const offset = Number(options.offset || 0);
    const params = new URLSearchParams();
    if (Number.isFinite(limit) && limit > 0) params.set("limit", String(Math.floor(limit)));
    if (Number.isFinite(offset) && offset >= 0) params.set("offset", String(Math.floor(offset)));
    const suffix = params.toString();
    const res = await fetch(`http://127.0.0.1:${settings.port}/history${suffix ? `?${suffix}` : ""}`);
    return await res.json();
  } catch {
    return {
      totalReceived: 0,
      totalBytes: 0,
      historyCount: 0,
      totalCount: 0,
      lastTransferAt: 0,
      offset: 0,
      nextOffset: 0,
      returnedCount: 0,
      hasMore: false,
      items: [],
    };
  }
});

ipcMain.handle("clear-history", async () => {
  try {
    const res = await localFetch(`/history/clear`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: "{}",
    });
    if (!res.ok) return { success: false };
    return await res.json();
  } catch {
    return { success: false };
  }
});

ipcMain.handle("export-history", async () => {
  const stamp = new Date().toISOString().slice(0, 10);
  const result = await dialog.showSaveDialog(mainWindow, {
    title: "Export Pherry history",
    defaultPath: path.join(app.getPath("documents"), `Pherry-history-${stamp}.json`),
    filters: [{ name: "JSON", extensions: ["json"] }],
  });
  if (result.canceled || !result.filePath) return { success: false, canceled: true };

  try {
    const fallbackState = {
      version: 2,
      totalReceived: 0,
      totalBytes: 0,
      activityLog: [],
      completedFiles: [],
      completedMd5s: [],
    };
    const raw = fs.existsSync(historyStatePath)
      ? fs.readFileSync(historyStatePath, "utf8")
      : JSON.stringify(fallbackState, null, 2);
    JSON.parse(raw);
    fs.writeFileSync(result.filePath, raw, "utf8");
    return { success: true, filePath: result.filePath };
  } catch (err) {
    return { success: false, error: err.message };
  }
});

ipcMain.handle("import-history", async () => {
  const result = await dialog.showOpenDialog(mainWindow, {
    title: "Import Pherry history",
    properties: ["openFile"],
    filters: [{ name: "JSON", extensions: ["json"] }],
  });
  if (result.canceled || !result.filePaths[0]) return { success: false, canceled: true };

  try {
    const raw = fs.readFileSync(result.filePaths[0], "utf8");
    const parsed = JSON.parse(raw);
    const looksLikeHistory =
      parsed &&
      typeof parsed === "object" &&
      (
        Array.isArray(parsed.activityLog) ||
        Array.isArray(parsed.completedFiles) ||
        Array.isArray(parsed.completedMd5s)
      );
    if (!looksLikeHistory) {
      return { success: false, error: "That file does not look like a Pherry history export." };
    }

    fs.mkdirSync(path.dirname(historyStatePath), { recursive: true });
    fs.writeFileSync(historyStatePath, JSON.stringify(parsed, null, 2), "utf8");
    await startServer();
    return { success: true, filePath: result.filePaths[0] };
  } catch (err) {
    return { success: false, error: err.message };
  }
});

ipcMain.handle("rebuild-history-index", async () => {
  try {
    const res = await fetch(`http://127.0.0.1:${settings.port}/history/rebuild-index`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: "{}",
    });
    if (!res.ok) return { success: false };
    return await res.json();
  } catch (err) {
    return { success: false, error: err.message };
  }
});

ipcMain.handle("rebuild-history-progress", async () => {
  try {
    const res = await fetch(`http://127.0.0.1:${settings.port}/history/rebuild-progress`);
    if (!res.ok) return { success: false };
    return { success: true, ...(await res.json()) };
  } catch (err) {
    return { success: false, error: err.message };
  }
});

ipcMain.handle("remove-duplicates", async (_e, opts = {}) => {
  try {
    const res = await localFetch(`/history/remove-duplicates`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ dryRun: !!opts.dryRun }),
    });
    if (!res.ok) return { success: false };
    return await res.json();
  } catch (err) {
    return { success: false, error: err.message };
  }
});

ipcMain.handle("get-local-ips", () => getLocalIPs());

ipcMain.handle("get-server-state", () => ({ ...lastServerState }));

ipcMain.handle("get-host-info", () => ({
  hostname: os.hostname(),
  platform: process.platform,
  version: app.getVersion(),
}));

ipcMain.handle("get-settings", () => ({ ...settings }));

// Generate a fresh pairing code and restart the receiver so the new token immediately gates
// destructive routes. Paired phones keep working for uploads but must re-scan the QR to regain
// delete/clean rights — that's the point of rotating (e.g. after sharing the code with a guest).
ipcMain.handle("rotate-pairing-token", async () => {
  settings.pairingToken = generatePairingToken();
  saveSettings();
  try {
    await startServer();
  } catch (err) {
    return { success: false, error: err.message, pairingToken: settings.pairingToken };
  }
  refreshTray();
  return { success: true, pairingToken: settings.pairingToken };
});

ipcMain.handle("update-settings", async (_e, patch = {}) => {
  const prev = { ...settings };
  const next = { ...settings };
  let needsRestart = false;

  if (typeof patch.theme === "string" && ["system", "light", "dark"].includes(patch.theme)) {
    next.theme = patch.theme;
  }
  if (typeof patch.autoOpenFolder === "boolean") next.autoOpenFolder = patch.autoOpenFolder;
  if (typeof patch.launchAtStartup === "boolean") next.launchAtStartup = patch.launchAtStartup;
  if (typeof patch.minimizeToTray === "boolean") next.minimizeToTray = patch.minimizeToTray;
  if (typeof patch.notifyOnArrival === "boolean") next.notifyOnArrival = patch.notifyOnArrival;
  if (typeof patch.port === "number" || typeof patch.port === "string") {
    const p = Math.floor(Number(patch.port));
    if (Number.isFinite(p) && p >= MIN_PORT && p <= MAX_PORT && p !== prev.port) {
      next.port = p;
      needsRestart = true;
    }
  }
  if (typeof patch.downloadPath === "string" && patch.downloadPath && patch.downloadPath !== prev.downloadPath) {
    next.downloadPath = patch.downloadPath;
    needsRestart = true;
  }

  settings = next;
  saveSettings();

  if (next.launchAtStartup !== prev.launchAtStartup) applyLaunchAtStartup();

  if (needsRestart) {
    try {
      await startServer();
      return { success: true, settings: { ...settings }, restarted: true };
    } catch (err) {
      // Rollback breaking changes
      settings = prev;
      saveSettings();
      try {
        await startServer();
      } catch {
        /* fatal — UI will see server-state error */
      }
      return { success: false, error: err.message, settings: { ...settings } };
    }
  }

  return { success: true, settings: { ...settings }, restarted: false };
});

ipcMain.handle("choose-folder", async () => {
  const result = await dialog.showOpenDialog(mainWindow, {
    properties: ["openDirectory"],
    defaultPath: settings.downloadPath,
  });
  if (!result.canceled && result.filePaths[0]) {
    const newPath = result.filePaths[0];
    if (newPath !== settings.downloadPath) {
      settings.downloadPath = newPath;
      saveSettings();
      try {
        await startServer();
      } catch {
        /* ignore — surfaced via server-state */
      }
    }
    return settings.downloadPath;
  }
  return settings.downloadPath;
});

ipcMain.handle("open-folder", async (_e, p) => {
  const target = p || settings.downloadPath;
  if (fs.existsSync(target)) {
    await shell.openPath(target);
  }
});

ipcMain.handle("open-external", async (_e, url) => {
  if (typeof url === "string" && /^https?:\/\//i.test(url)) {
    await shell.openExternal(url);
  }
});

// ── Files: thumbnails + open/reveal ──────────────────────────────────────────

/** Resolve a {bucket,name} pair to an absolute path strictly inside the download folder. */
function resolveDownloadFile(bucket, name) {
  const safeBucket = String(bucket || "Unsorted").replace(/\.\./g, "").replace(/^[\\/]+/, "");
  const safeName = path.basename(String(name || ""));
  if (!safeName) return null;
  const root = path.resolve(settings.downloadPath);
  const full = path.resolve(path.join(root, safeBucket, safeName));
  if (full !== root && !full.startsWith(root + path.sep)) return null; // path-traversal guard
  return full;
}

const IMAGE_EXTS = new Set([".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".heic", ".heif", ".tif", ".tiff"]);
const thumbnailCache = new Map(); // key `path:mtime` -> dataURL
// 256px thumbnails so a ~160px contact-sheet frame stays sharp on a 2x display. They are about four
// times the size of the old 128px ones, so the cache is also capped by its total length.
const THUMB_SIZE = 256;
const THUMB_CACHE_MAX_CHARS = 64 * 1024 * 1024;
let thumbnailCacheChars = 0;

ipcMain.handle("get-thumbnail", async (_e, opts = {}) => {
  try {
    const full = resolveDownloadFile(opts.bucket, opts.name);
    if (!full || !fs.existsSync(full)) return null;
    if (!IMAGE_EXTS.has(path.extname(full).toLowerCase())) return null;
    const stat = fs.statSync(full);
    const key = `${full}:${stat.mtimeMs}`;
    if (thumbnailCache.has(key)) return thumbnailCache.get(key);
    const img = await nativeImage.createThumbnailFromPath(full, { width: THUMB_SIZE, height: THUMB_SIZE });
    const dataUrl = img.isEmpty() ? null : img.toDataURL();
    if (dataUrl) {
      if (thumbnailCache.size > 600 || thumbnailCacheChars + dataUrl.length > THUMB_CACHE_MAX_CHARS) {
        thumbnailCache.clear();
        thumbnailCacheChars = 0;
      }
      thumbnailCache.set(key, dataUrl);
      thumbnailCacheChars += dataUrl.length;
    }
    return dataUrl;
  } catch {
    return null;
  }
});

ipcMain.handle("reveal-file", async (_e, opts = {}) => {
  const full = resolveDownloadFile(opts.bucket, opts.name);
  if (full && fs.existsSync(full)) {
    shell.showItemInFolder(full);
    return true;
  }
  return false;
});

ipcMain.handle("open-file", async (_e, opts = {}) => {
  const full = resolveDownloadFile(opts.bucket, opts.name);
  if (full && fs.existsSync(full)) {
    await shell.openPath(full);
    return true;
  }
  return false;
});

// ── Tray ─────────────────────────────────────────────────────────────────────

function trayImage() {
  try {
    const img = nativeImage.createFromPath(path.join(__dirname, "renderer", "tray-icon.png"));
    return img.isEmpty() ? null : img;
  } catch {
    return null;
  }
}

function showMainWindow() {
  if (!mainWindow || mainWindow.isDestroyed()) {
    createWindow();
    return;
  }
  if (mainWindow.isMinimized()) mainWindow.restore();
  mainWindow.show();
  mainWindow.focus();
}

function buildTrayMenu() {
  const ip = sortedLocalIPs()[0];
  const address = ip ? `${ip}:${settings.port}` : "not on a network";
  return Menu.buildFromTemplate([
    { label: "Open Pherry", click: () => showMainWindow() },
    { label: `Address: ${address}`, enabled: false },
    { label: `Pairing code: ${settings.pairingToken}`, enabled: false },
    { type: "separator" },
    { label: "Open the Pherry folder", click: () => fs.existsSync(settings.downloadPath) && shell.openPath(settings.downloadPath) },
    { type: "separator" },
    {
      label: "Quit Pherry",
      click: () => {
        app.isQuitting = true;
        app.quit();
      },
    },
  ]);
}

function createTray() {
  if (tray) return;
  const img = trayImage();
  tray = img ? new Tray(img) : new Tray(nativeImage.createEmpty());
  updateTrayTooltip();
  tray.setContextMenu(buildTrayMenu());
  tray.on("click", () => showMainWindow());
  tray.on("double-click", () => showMainWindow());
}

function refreshTray() {
  if (tray) tray.setContextMenu(buildTrayMenu());
}

function updateTrayTooltip() {
  if (!tray) return;
  const s = lastServerState;
  tray.setToolTip(
    s.running
      ? "Pherry · receiving"
      : s.code === "EADDRINUSE"
        ? `Pherry · not receiving, port ${s.port} is in use`
        : "Pherry · not receiving"
  );
}

// Wi-Fi can come up after Pherry starts at sign-in, and DHCP can hand out a new address. Watch for
// it so the tray menu and the window's pairing ticket never show a stale address.
let lastIPs = "";
function watchNetwork() {
  lastIPs = sortedLocalIPs().join(",");
  setInterval(() => {
    const now = sortedLocalIPs().join(",");
    if (now === lastIPs) return;
    lastIPs = now;
    refreshTray();
    broadcastToRenderer("ips-changed", now ? now.split(",") : []);
  }, 10000);
}

// ── App lifecycle ──────────────────────────────────────────────────────────

app.whenReady().then(async () => {
  if (!gotSingleInstanceLock) return;
  historyStatePath = path.join(app.getPath("userData"), "history-state.json");
  settingsPath = path.join(app.getPath("userData"), "settings.json");
  loadSettings();
  applyLaunchAtStartup();
  lastServerState = { ...lastServerState, port: settings.port };
  try {
    await startServer();
  } catch (err) {
    // Surfaced through lastServerState: the window asks for it (get-server-state) once it loads.
    if (!lastServerState.error) {
      setServerState({ running: false, port: settings.port, error: err?.message || "The receiver couldn't start" });
    }
  }
  createTray();
  refreshTray();
  createWindow();
  watchNetwork();
});

app.on("before-quit", () => {
  app.isQuitting = true;
});

// The receiver is meant to keep running in the tray. Only actually quit when the user
// chose Quit (app.isQuitting) or the platform has no tray to fall back to.
app.on("window-all-closed", async () => {
  // Keep running in the tray only if we actually have a tray to restore from.
  if (app.isQuitting || !settings.minimizeToTray || !tray) {
    await stopServer();
    app.quit();
  }
});

app.on("activate", () => {
  if (BrowserWindow.getAllWindows().length === 0) createWindow();
  else showMainWindow();
});
