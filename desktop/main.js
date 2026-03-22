const { app, BrowserWindow, ipcMain, dialog, shell } = require("electron");
const path = require("path");
const fs = require("fs");
const { createServer, getLocalIPs } = require("./server");

const PORT = 3210;
let mainWindow;
let downloadPath = path.join(app.getPath("pictures"), "PhotoSender");
let serverInstance;

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1280,
    height: 820,
    minWidth: 960,
    minHeight: 640,
    title: "PhotoSender Desktop",
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
    },
    show: false,
    backgroundColor: "#f7f9fb",
  });

  mainWindow.loadFile(path.join(__dirname, "renderer", "index.html"));

  mainWindow.once("ready-to-show", () => mainWindow.show());
  mainWindow.on("closed", () => {
    mainWindow = null;
  });
}

function startServer() {
  fs.mkdirSync(downloadPath, { recursive: true });
  const expressApp = createServer(downloadPath);

  serverInstance = expressApp.listen(PORT, "0.0.0.0", () => {
    console.log(`PhotoSender server listening on port ${PORT}`);
    const ips = getLocalIPs();
    console.log("Local IPs:", ips.join(", "));
  });
}

// IPC handlers
ipcMain.handle("get-status", async () => {
  try {
    const res = await fetch(`http://127.0.0.1:${PORT}/status`);
    return await res.json();
  } catch {
    return { totalReceived: 0, totalBytes: 0, uptimeMs: 0, recentActivity: [] };
  }
});

ipcMain.handle("get-local-ips", () => getLocalIPs());

ipcMain.handle("get-download-path", () => downloadPath);

ipcMain.handle("get-port", () => PORT);

ipcMain.handle("choose-folder", async () => {
  const result = await dialog.showOpenDialog(mainWindow, {
    properties: ["openDirectory"],
    defaultPath: downloadPath,
  });
  if (!result.canceled && result.filePaths[0]) {
    downloadPath = result.filePaths[0];
    // Restart server with new path
    if (serverInstance) {
      serverInstance.close(() => startServer());
    }
    return downloadPath;
  }
  return downloadPath;
});

ipcMain.handle("open-folder", async (_e, p) => {
  const target = p || downloadPath;
  if (fs.existsSync(target)) {
    await shell.openPath(target);
  }
});

// Forward SSE events from Express to renderer
process.on("message", (msg) => {
  if (msg.type === "file-received" && mainWindow) {
    mainWindow.webContents.send("file-received", msg.data);
  }
});

app.whenReady().then(() => {
  startServer();
  createWindow();
});

app.on("window-all-closed", () => {
  if (serverInstance) serverInstance.close();
  app.quit();
});

app.on("activate", () => {
  if (BrowserWindow.getAllWindows().length === 0) createWindow();
});
