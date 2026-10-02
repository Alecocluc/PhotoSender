const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("api", {
  // Queries
  getStatus: () => ipcRenderer.invoke("get-status"),
  getHistory: (options) => ipcRenderer.invoke("get-history", options),
  getMedia: (options) => ipcRenderer.invoke("get-media", options),
  getJobs: () => ipcRenderer.invoke("get-jobs"),
  getJob: (options) => ipcRenderer.invoke("get-job", options),
  getDevices: () => ipcRenderer.invoke("get-devices"),
  renameDevice: (options) => ipcRenderer.invoke("rename-device", options),
  revokeDevice: (options) => ipcRenderer.invoke("revoke-device", options),
  getLocalIPs: () => ipcRenderer.invoke("get-local-ips"),
  getSettings: () => ipcRenderer.invoke("get-settings"),
  getHostInfo: () => ipcRenderer.invoke("get-host-info"),
  getServerState: () => ipcRenderer.invoke("get-server-state"),

  // Mutations
  clearHistory: () => ipcRenderer.invoke("clear-history"),
  exportHistory: () => ipcRenderer.invoke("export-history"),
  importHistory: () => ipcRenderer.invoke("import-history"),
  rebuildHistoryIndex: () => ipcRenderer.invoke("rebuild-history-index"),
  rebuildHistoryProgress: () => ipcRenderer.invoke("rebuild-history-progress"),
  removeDuplicates: (opts) => ipcRenderer.invoke("remove-duplicates", opts),
  chooseFolder: () => ipcRenderer.invoke("choose-folder"),
  openFolder: (p) => ipcRenderer.invoke("open-folder", p),
  openExternal: (url) => ipcRenderer.invoke("open-external", url),
  updateSettings: (patch) => ipcRenderer.invoke("update-settings", patch),
  rotatePairingToken: () => ipcRenderer.invoke("rotate-pairing-token"),

  // Files (thumbnails + reveal/open in OS file manager)
  getThumbnail: (opts) => ipcRenderer.invoke("get-thumbnail", opts),
  revealFile: (opts) => ipcRenderer.invoke("reveal-file", opts),
  openFile: (opts) => ipcRenderer.invoke("open-file", opts),

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
  onFilesRemoved: (cb) => {
    const handler = (_e, data) => cb(data);
    ipcRenderer.on("files-removed", handler);
    return () => ipcRenderer.removeListener("files-removed", handler);
  },
  onIpsChanged: (cb) => {
    const handler = (_e, data) => cb(data);
    ipcRenderer.on("ips-changed", handler);
    return () => ipcRenderer.removeListener("ips-changed", handler);
  },
  onJobsChanged: (cb) => {
    const handler = (_e, data) => cb(data);
    ipcRenderer.on("jobs-changed", handler);
    return () => ipcRenderer.removeListener("jobs-changed", handler);
  },
  onDevicesChanged: (cb) => {
    const handler = (_e, data) => cb(data);
    ipcRenderer.on("devices-changed", handler);
    return () => ipcRenderer.removeListener("devices-changed", handler);
  },
});
