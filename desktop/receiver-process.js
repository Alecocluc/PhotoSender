const { createServer } = require("./receiver");
let httpServer, app;
const send = (message) => process.parentPort.postMessage(message);
process.parentPort.on("message", async ({ data }) => {
  if (data.type === "start") {
    try {
      app = createServer(data.downloadPath, {
        ...data.options,
        onRecoveryProgress: () => send({ type: "recovery-progress" }),
        onFileReceived: (entry) => send({ type: "file-received", data: entry }),
        onFilesRemoved: (info) => send({ type: "files-removed", data: info }),
        onJobsChanged: (info) => send({ type: "jobs-changed", data: info }),
        onDevicesChanged: (info) =>
          send({ type: "devices-changed", data: info }),
      });
      await app.locals.ready;
      httpServer = app.listen(data.port, "0.0.0.0", () =>
        send({ type: "ready" }),
      );
      httpServer.requestTimeout = 0;
      httpServer.setTimeout(120000);
      httpServer.on("error", (e) =>
        send({ type: "error", error: e.message, code: e.code }),
      );
    } catch (e) {
      send({ type: "error", error: e.message, code: e.code });
    }
  }
  if (data.type === "stop") {
    if (!httpServer) {
      app?.locals.close();
      process.exit(0);
      return;
    }
    // Chunks are small and acknowledged durably. Finish requests before closing the database.
    httpServer.close(() => {
      app.locals.close();
      process.exit(0);
    });
    setTimeout(() => {
      httpServer.closeAllConnections();
    }, 1500).unref();
  }
});
