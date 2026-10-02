const {
  app,
  BrowserWindow,
  ipcMain,
  dialog,
  shell,
  Tray,
  Menu,
  Notification,
  nativeImage,
  nativeTheme,
  utilityProcess,
  powerSaveBlocker,
} = require("electron");
const path = require("path");
const fs = require("fs");
const crypto = require("crypto");
const os = require("os");
const { pathToFileURL } = require("node:url");
const { inside } = require("./storage");
const getLocalIPs = () => [
  ...new Set(
    Object.values(os.networkInterfaces())
      .flat()
      .filter((a) => a && a.family === "IPv4" && !a.internal)
      .map((a) => a.address),
  ),
];
// Isolated development smoke tests must never touch the user's receiver or settings.
if (!app.isPackaged && process.env.PHERRY_TEST_USER_DATA)
  app.setPath("userData", process.env.PHERRY_TEST_USER_DATA);
let adminToken = crypto.randomBytes(32).toString("base64url");
let sleepBlocker = null;
let shuttingDown = false;
const jobStates = new Map();

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
let lastServerState = {
  running: false,
  port: DEFAULT_PORT,
  error: null,
  code: null,
};

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
  if (typeof settings.minimizeToTray !== "boolean")
    settings.minimizeToTray = true;
  if (typeof settings.notifyOnArrival !== "boolean")
    settings.notifyOnArrival = true;
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
  const dark =
    settings.theme === "dark" ||
    (settings.theme === "system" && nativeTheme.shouldUseDarkColors);
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
      sandbox: true,
    },
    icon: path.join(__dirname, "renderer", "pherry-icon.png"),
    show: false,
    backgroundColor: windowBackground(),
  });

  mainWindow.webContents.on("will-navigate", (event) => event.preventDefault());
  mainWindow.webContents.setWindowOpenHandler(() => ({ action: "deny" }));
  mainWindow.webContents.session.setPermissionRequestHandler(
    (_contents, _permission, callback) => callback(false),
  );
  mainWindow.loadFile(path.join(__dirname, "renderer", "index.html"));
  // A bind failure at launch happens before any window exists; repeat the state once the page is up.
  mainWindow.webContents.on("did-finish-load", () =>
    broadcastToRenderer("server-state", { ...lastServerState }),
  );

  mainWindow.once("ready-to-show", () => {
    if (!process.env.PHERRY_TEST_USER_DATA) mainWindow.show();
  });

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
  lastServerState = {
    running: false,
    port: settings.port,
    error: null,
    code: null,
    ...next,
  };
  updateTrayTooltip();
  broadcastToRenderer("server-state", { ...lastServerState });
}

/** Same order as the renderer's sortedIPs(): home Wi-Fi ranges first, VPN and virtual adapters last. */
function sortedLocalIPs() {
  const score = (ip) =>
    /^192\.168\./.test(ip)
      ? 0
      : /^10\./.test(ip)
        ? 1
        : /^172\./.test(ip)
          ? 2
          : 3;
  return [...(getLocalIPs() || [])].sort((a, b) => score(a) - score(b));
}

function formatBytes(bytes) {
  if (!bytes || bytes <= 0) return "0 B";
  const units = ["B", "KB", "MB", "GB", "TB"];
  let i = 0;
  let v = bytes;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  return `${v.toFixed(v >= 10 || i === 0 ? 0 : 1)} ${units[i]}`;
}

// ── mDNS / DNS-SD advertising ────────────────────────────────────────────────
// Publishes "_pherry._tcp" so the phone can discover this receiver with no QR/typing,
// and survive the PC's IP changing on DHCP renewal.
function stopBonjour() {
  try {
    if (bonjourService) bonjourService.stop();
  } catch {
    /* ignore */
  }
  bonjourService = null;
  try {
    if (bonjourInstance) bonjourInstance.destroy();
  } catch {
    /* ignore */
  }
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
      txt: { host: os.hostname(), v: "2", id: settings.deviceId },
    });
  } catch (err) {
    console.error("mDNS publish failed:", err.message);
  }
}

async function stopServer() {
  stopBonjour();
  const child = serverInstance;
  serverInstance = null;
  if (sleepBlocker !== null) {
    powerSaveBlocker.stop(sleepBlocker);
    sleepBlocker = null;
  }
  if (!child) return;
  await new Promise((resolve) => {
    const timeout = setTimeout(() => {
      child.kill();
      resolve();
    }, 4000);
    child.once("exit", () => {
      clearTimeout(timeout);
      resolve();
    });
    child.postMessage({ type: "stop" });
  });
}

function handleJobs(info) {
  const jobs = info?.items || [];
  const busy = jobs.some((j) => ["planning", "running"].includes(j.state));
  if (busy && sleepBlocker === null)
    sleepBlocker = powerSaveBlocker.start("prevent-app-suspension");
  if (!busy && sleepBlocker !== null) {
    powerSaveBlocker.stop(sleepBlocker);
    sleepBlocker = null;
  }
  for (const job of jobs) {
    const before = jobStates.get(job.id);
    jobStates.set(job.id, job.state);
    if (
      before &&
      before !== job.state &&
      job.state === "completed" &&
      settings.notifyOnArrival &&
      Notification.isSupported() &&
      !mainWindow?.isFocused()
    ) {
      const notice = new Notification({
        title: `Backup from ${job.deviceName} finished`,
        body: `${job.completedFiles || 0} saved · ${job.skippedFiles || 0} already present · ${job.failedFiles || 0} failed`,
        silent: true,
      });
      notice.on("click", showMainWindow);
      notice.show();
    }
  }
  broadcastToRenderer("jobs-changed", info);
}

async function startServer() {
  await stopServer();
  fs.mkdirSync(settings.downloadPath, { recursive: true });
  thumbnailCache.clear();
  thumbnailCacheChars = 0;
  const child = utilityProcess.fork(
    path.join(__dirname, "receiver-process.js"),
    [],
    { serviceName: "Pherry Receiver", stdio: "pipe" },
  );
  serverInstance = child;
  child.stderr?.on("data", (data) => console.error(String(data).trim()));
  await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      child.kill();
      reject(new Error("The receiver took too long to start"));
    }, 30000);
    child.on("message", (message) => {
      if (message.type === "ready") {
        clearTimeout(timeout);
        startBonjour();
        refreshTray();
        setServerState({ running: true, port: settings.port });
        resolve();
      } else if (message.type === "error") {
        clearTimeout(timeout);
        setServerState({
          running: false,
          port: settings.port,
          error: message.error,
          code: message.code,
        });
        reject(new Error(message.error));
      } else if (message.type === "jobs-changed") handleJobs(message.data);
      else if (message.type === "file-received") {
        broadcastToRenderer(message.type, message.data);
        if (settings.autoOpenFolder && message.data.relativePath) {
          try {
            shell.openPath(
              path.dirname(
                inside(settings.downloadPath, message.data.relativePath),
              ),
            );
          } catch {
            /* stale receipt */
          }
        }
      } else if (["files-removed", "devices-changed"].includes(message.type))
        broadcastToRenderer(message.type, message.data);
    });
    child.once("exit", (code) => {
      clearTimeout(timeout);
      if (serverInstance === child) {
        serverInstance = null;
        if (sleepBlocker !== null) {
          powerSaveBlocker.stop(sleepBlocker);
          sleepBlocker = null;
        }
        setServerState({
          running: false,
          error: "The receiver stopped. Restart Pherry to resume your backup.",
          code,
        });
      }
      reject(new Error("The receiver stopped during startup"));
    });
    child.postMessage({
      type: "start",
      downloadPath: settings.downloadPath,
      port: settings.port,
      options: {
        historyStatePath,
        databasePath: path.join(app.getPath("userData"), "receiver.sqlite"),
        pairingToken: settings.pairingToken,
        deviceId: settings.deviceId,
        adminToken,
      },
    });
  });
}

async function localFetch(route, options = {}) {
  const res = await fetch(`http://127.0.0.1:${settings.port}${route}`, {
    ...options,
    signal: ["/history/import", "/history/remove-duplicates"].includes(route)
      ? undefined
      : AbortSignal.timeout(30000),
    headers: { ...options.headers, "X-Pherry-Admin": adminToken },
  });
  return res;
}
async function api(route, method = "GET", body) {
  const res = await localFetch(route, {
    method,
    ...(body === undefined
      ? {}
      : {
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(body),
        }),
  });
  const value = await res.json();
  if (!res.ok)
    throw new Error(
      value.error || "The receiver could not complete that action",
    );
  return value;
}
function query(options = {}) {
  const params = new URLSearchParams();
  for (const key of [
    "limit",
    "offset",
    "query",
    "kind",
    "type",
    "album",
    "deviceId",
    "sort",
    "dateFrom",
    "dateTo",
    "snapshot",
  ]) {
    if (["string", "number"].includes(typeof options[key]))
      params.set(key, String(options[key]).slice(0, 250));
  }
  return params.toString();
}
function handle(channel, fn) {
  ipcMain.handle(channel, (event, ...args) => {
    const expected = pathToFileURL(
      path.join(__dirname, "renderer", "index.html"),
    ).href;
    if (
      !mainWindow ||
      event.sender !== mainWindow.webContents ||
      event.senderFrame?.url !== expected
    )
      throw new Error("Untrusted desktop page");
    return fn(event, ...args);
  });
}

handle("get-status", () => api("/status"));
handle("get-history", (_event, options) => api(`/history?${query(options)}`));
handle("get-media", (_event, options) => api(`/v2/media?${query(options)}`));
handle("get-jobs", () => api("/v2/jobs"));
handle("get-job", (_event, options) =>
  api(`/v2/jobs/${encodeURIComponent(options?.id || options)}`),
);
handle("get-devices", () => api("/v2/devices"));
handle("rename-device", (_event, options) =>
  api(
    `/v2/devices/${encodeURIComponent(options.id || options.deviceId)}`,
    "PATCH",
    { name: options.name || options.deviceName },
  ),
);
handle("revoke-device", (_event, options) =>
  api(
    `/v2/devices/${encodeURIComponent(options.id || options.deviceId)}`,
    "DELETE",
  ),
);
handle("clear-history", () => api("/history/clear", "POST", {}));
handle("export-history", async () => {
  const result = await dialog.showSaveDialog(mainWindow, {
    title: "Export Pherry history",
    defaultPath: path.join(
      app.getPath("documents"),
      `Pherry-history-${new Date().toISOString().slice(0, 10)}.json`,
    ),
    filters: [{ name: "JSON", extensions: ["json"] }],
  });
  if (result.canceled || !result.filePath)
    return { success: false, canceled: true };
  try {
    const data = await api("/history/export");
    await fs.promises.writeFile(
      result.filePath,
      JSON.stringify(data, null, 2),
      "utf8",
    );
    return { success: true, filePath: result.filePath };
  } catch (err) {
    return { success: false, error: err.message };
  }
});
handle("import-history", async () => {
  const result = await dialog.showOpenDialog(mainWindow, {
    title: "Import Pherry history",
    properties: ["openFile"],
    filters: [{ name: "JSON", extensions: ["json"] }],
  });
  if (result.canceled || !result.filePaths[0])
    return { success: false, canceled: true };
  try {
    const stat = await fs.promises.stat(result.filePaths[0]);
    if (stat.size > 64 * 1024 * 1024)
      throw new Error("The history file is too large (maximum 64 MB)");
    const data = JSON.parse(
      await fs.promises.readFile(result.filePaths[0], "utf8"),
    );
    if (!Array.isArray(data.activityLog) && !Array.isArray(data.completedFiles))
      throw new Error("This is not a Pherry history export");
    return await api("/history/import", "POST", data);
  } catch (err) {
    return { success: false, error: err.message };
  }
});
handle("rebuild-history-index", () =>
  api("/history/rebuild-index", "POST", {}),
);
handle("rebuild-history-progress", () => api("/history/rebuild-progress"));
handle("remove-duplicates", (_event, opts = {}) =>
  api("/history/remove-duplicates", "POST", { dryRun: !!opts.dryRun }),
);

handle("get-local-ips", () => getLocalIPs());

handle("get-server-state", () => ({ ...lastServerState }));

handle("get-host-info", () => ({
  hostname: os.hostname(),
  platform: process.platform,
  version: app.getVersion(),
}));

handle("get-settings", () => ({ ...settings }));

// Enrollment-code rotation preserves established phone credentials. Revoke a phone separately.
handle("rotate-pairing-token", async () => {
  const code = generatePairingToken();
  try {
    await api("/v2/pairing-code", "POST", { code });
    settings.pairingToken = code;
    saveSettings();
    refreshTray();
    return { success: true, pairingToken: code };
  } catch (err) {
    return { success: false, error: err.message };
  }
});

handle("update-settings", async (_e, patch = {}) => {
  const prev = { ...settings };
  const next = { ...settings };
  let needsRestart = false;

  if (
    typeof patch.theme === "string" &&
    ["system", "light", "dark"].includes(patch.theme)
  ) {
    next.theme = patch.theme;
  }
  if (typeof patch.autoOpenFolder === "boolean")
    next.autoOpenFolder = patch.autoOpenFolder;
  if (typeof patch.launchAtStartup === "boolean")
    next.launchAtStartup = patch.launchAtStartup;
  if (typeof patch.minimizeToTray === "boolean")
    next.minimizeToTray = patch.minimizeToTray;
  if (typeof patch.notifyOnArrival === "boolean")
    next.notifyOnArrival = patch.notifyOnArrival;
  if (typeof patch.port === "number" || typeof patch.port === "string") {
    const p = Math.floor(Number(patch.port));
    if (
      Number.isFinite(p) &&
      p >= MIN_PORT &&
      p <= MAX_PORT &&
      p !== prev.port
    ) {
      next.port = p;
      needsRestart = true;
    }
  }
  if (
    typeof patch.downloadPath === "string" &&
    patch.downloadPath &&
    patch.downloadPath !== prev.downloadPath
  ) {
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

handle("choose-folder", async () => {
  const result = await dialog.showOpenDialog(mainWindow, {
    properties: ["openDirectory"],
    defaultPath: settings.downloadPath,
  });
  if (!result.canceled && result.filePaths[0]) {
    const newPath = result.filePaths[0];
    if (newPath !== settings.downloadPath) {
      const previousPath = settings.downloadPath;
      settings.downloadPath = newPath;
      saveSettings();
      try {
        await startServer();
      } catch (error) {
        settings.downloadPath = previousPath;
        saveSettings();
        await startServer().catch(() => {});
        throw error;
      }
    }
    return settings.downloadPath;
  }
  return settings.downloadPath;
});

handle("open-folder", async (_e, p) => {
  let target = settings.downloadPath;
  if (p && p !== settings.downloadPath) {
    try {
      target = inside(
        settings.downloadPath,
        path.isAbsolute(p) ? path.relative(settings.downloadPath, p) : p,
      );
    } catch {
      return false;
    }
  }
  if (fs.existsSync(target)) {
    await shell.openPath(target);
  }
});

handle("open-external", async (_e, url) => {
  if (typeof url === "string" && /^https?:\/\//i.test(url)) {
    await shell.openExternal(url);
  }
});

// ── Files: thumbnails + open/reveal ──────────────────────────────────────────

/** Resolve a {bucket,name} pair to an absolute path strictly inside the download folder. */
function resolveDownloadFile(opts = {}) {
  try {
    return inside(
      settings.downloadPath,
      opts.relativePath ||
        path.join(
          opts.deviceFolder || "",
          opts.bucket || "Unsorted",
          path.basename(String(opts.name || "")),
        ),
    );
  } catch {
    return null;
  }
}

const MEDIA_EXTS = new Set([
  ".jpg",
  ".jpeg",
  ".png",
  ".gif",
  ".webp",
  ".bmp",
  ".heic",
  ".heif",
  ".tif",
  ".tiff",
  ".mp4",
  ".mov",
  ".m4v",
  ".webm",
  ".mkv",
  ".avi",
  ".3gp",
]);
const thumbnailCache = new Map(); // key `path:mtime` -> dataURL
// 256px thumbnails so a ~160px contact-sheet frame stays sharp on a 2x display. They are about four
// times the size of the old 128px ones, so the cache is also capped by its total length.
const THUMB_SIZE = 256;
const THUMB_CACHE_MAX_CHARS = 64 * 1024 * 1024;
let thumbnailCacheChars = 0;

handle("get-thumbnail", async (_e, opts = {}) => {
  try {
    const full = resolveDownloadFile(opts);
    if (!full || !fs.existsSync(full)) return null;
    if (!MEDIA_EXTS.has(path.extname(full).toLowerCase())) return null;
    const stat = fs.statSync(full);
    const key = `${full}:${stat.mtimeMs}`;
    if (thumbnailCache.has(key)) {
      const cached = thumbnailCache.get(key);
      thumbnailCache.delete(key);
      thumbnailCache.set(key, cached);
      return cached;
    }
    const img = await nativeImage.createThumbnailFromPath(full, {
      width: THUMB_SIZE,
      height: THUMB_SIZE,
    });
    const dataUrl = img.isEmpty() ? null : img.toDataURL();
    if (dataUrl) {
      while (
        thumbnailCache.size &&
        (thumbnailCache.size >= 600 ||
          thumbnailCacheChars + dataUrl.length > THUMB_CACHE_MAX_CHARS)
      ) {
        const oldest = thumbnailCache.keys().next().value;
        thumbnailCacheChars -= thumbnailCache.get(oldest).length;
        thumbnailCache.delete(oldest);
      }
      thumbnailCache.set(key, dataUrl);
      thumbnailCacheChars += dataUrl.length;
    }
    return dataUrl;
  } catch {
    return null;
  }
});

handle("reveal-file", async (_e, opts = {}) => {
  const full = resolveDownloadFile(opts);
  if (full && fs.existsSync(full)) {
    shell.showItemInFolder(full);
    return true;
  }
  return false;
});

handle("open-file", async (_e, opts = {}) => {
  const full = resolveDownloadFile(opts);
  if (full && fs.existsSync(full)) {
    const error = await shell.openPath(full);
    return !error;
  }
  return false;
});

// ── Tray ─────────────────────────────────────────────────────────────────────

function trayImage() {
  try {
    const img = nativeImage.createFromPath(
      path.join(__dirname, "renderer", "tray-icon.png"),
    );
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
    {
      label: "Open the Pherry folder",
      click: () =>
        fs.existsSync(settings.downloadPath) &&
        shell.openPath(settings.downloadPath),
    },
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
        : "Pherry · not receiving",
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
      setServerState({
        running: false,
        port: settings.port,
        error: err?.message || "The receiver couldn't start",
      });
    }
  }
  createTray();
  refreshTray();
  createWindow();
  watchNetwork();
});

app.on("before-quit", (event) => {
  app.isQuitting = true;
  if (!shuttingDown && serverInstance) {
    event.preventDefault();
    shuttingDown = true;
    stopServer().finally(() => app.quit());
  }
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
