const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("api", {
  // Queries
  getStatus: () => ipcRenderer.invoke("get-status"),
  getHistory: (options) => ipcRenderer.invoke("get-history", options),
  getLocalIPs: () => ipcRenderer.invoke("get-local-ips"),
  getSettings: () => ipcRenderer.invoke("get-settings"),

  // Mutations
  clearHistory: () => ipcRenderer.invoke("clear-history"),
  chooseFolder: () => ipcRenderer.invoke("choose-folder"),
  openFolder: (p) => ipcRenderer.invoke("open-folder", p),
  openExternal: (url) => ipcRenderer.invoke("open-external", url),
  updateSettings: (patch) => ipcRenderer.invoke("update-settings", patch),

  // Push events (from main → renderer)
  onFileReceived: (cb) => {
    const handler = (_e, data) => cb(data);
    ipcRenderer.on("file-received", handler);
    return () => ipcRenderer.removeListener("file-received", handler);
  },
  onServerState: (cb) => {
    const handler = (_e, data) => cb(data);
    ipcRenderer.on("server-state", handler);
    return () => ipcRenderer.removeListener("server-state", handler);
  },
});
