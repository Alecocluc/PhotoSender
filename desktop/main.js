const { app, BrowserWindow, ipcMain, dialog, shell } = require("electron");
const path = require("path");
const fs = require("fs");
const { createServer, getLocalIPs } = require("./server");

const DEFAULT_PORT = 3210;
const MIN_PORT = 1024;
const MAX_PORT = 65535;

let mainWindow;
let serverInstance;
let historyStatePath;
let settingsPath;

let settings = {
  downloadPath: "",
  port: DEFAULT_PORT,
  theme: "system", // 'system' | 'light' | 'dark'
  autoOpenFolder: false,
  launchAtStartup: false,
};

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

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1280,
    height: 820,
    minWidth: 480,
    minHeight: 560,
    title: "Pherry Desktop",
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
    },
    show: false,
    backgroundColor: settings.theme === "dark" ? "#0f172a" : "#f7f9fb",
  });

  mainWindow.loadFile(path.join(__dirname, "renderer", "index.html"));

  mainWindow.once("ready-to-show", () => mainWindow.show());
  mainWindow.on("closed", () => {
    mainWindow = null;
  });
}

function broadcastToRenderer(channel, payload) {
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send(channel, payload);
  }
}

function stopServer() {
  return new Promise((resolve) => {
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
    onFileReceived: (entry) => {
      broadcastToRenderer("file-received", entry);
      if (settings.autoOpenFolder) {
        const bucket = entry?.bucketName || "Unsorted";
        const target = path.join(settings.downloadPath, bucket);
        if (fs.existsSync(target)) shell.openPath(target);
      }
    },
  });

  return new Promise((resolve, reject) => {
    const server = expressApp.listen(settings.port, "0.0.0.0", () => {
      serverInstance = server;
      console.log(`Pherry server listening on port ${settings.port}`);
      broadcastToRenderer("server-state", { running: true, port: settings.port, error: null });
      resolve();
    });
    server.once("error", (err) => {
      console.error("Server bind error:", err.message);
      broadcastToRenderer("server-state", { running: false, port: settings.port, error: err.message });
      reject(err);
    });
  });
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
    const res = await fetch(`http://127.0.0.1:${settings.port}/history/clear`, {
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
    const res = await fetch(`http://127.0.0.1:${settings.port}/history/remove-duplicates`, {
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

ipcMain.handle("get-settings", () => ({ ...settings }));

ipcMain.handle("update-settings", async (_e, patch = {}) => {
  const prev = { ...settings };
  const next = { ...settings };
  let needsRestart = false;

  if (typeof patch.theme === "string" && ["system", "light", "dark"].includes(patch.theme)) {
    next.theme = patch.theme;
  }
  if (typeof patch.autoOpenFolder === "boolean") next.autoOpenFolder = patch.autoOpenFolder;
  if (typeof patch.launchAtStartup === "boolean") next.launchAtStartup = patch.launchAtStartup;
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

// ── App lifecycle ──────────────────────────────────────────────────────────

app.whenReady().then(async () => {
  historyStatePath = path.join(app.getPath("userData"), "history-state.json");
  settingsPath = path.join(app.getPath("userData"), "settings.json");
  loadSettings();
  applyLaunchAtStartup();
  try {
    await startServer();
  } catch {
    /* surfaced via server-state event */
  }
  createWindow();
});

app.on("window-all-closed", async () => {
  await stopServer();
  app.quit();
});

app.on("activate", () => {
  if (BrowserWindow.getAllWindows().length === 0) createWindow();
});
