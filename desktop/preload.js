const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("api", {
  getStatus: () => ipcRenderer.invoke("get-status"),
  getLocalIPs: () => ipcRenderer.invoke("get-local-ips"),
  getDownloadPath: () => ipcRenderer.invoke("get-download-path"),
  chooseFolder: () => ipcRenderer.invoke("choose-folder"),
  openFolder: (p) => ipcRenderer.invoke("open-folder", p),
  getPort: () => ipcRenderer.invoke("get-port"),

  // SSE-like events from main process
  onFileReceived: (cb) => {
    ipcRenderer.on("file-received", (_e, data) => cb(data));
  },
  onStatsUpdate: (cb) => {
    ipcRenderer.on("stats-update", (_e, data) => cb(data));
  },
});
