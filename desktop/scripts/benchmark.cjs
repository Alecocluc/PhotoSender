// Synthetic loopback receiver benchmark. This does not measure phone storage or Wi-Fi throughput.
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const crypto = require("node:crypto");
const { performance } = require("node:perf_hooks");
const { createServer } = require("../receiver");

async function measure(parallel) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "pherry-benchmark-"));
  const app = createServer(path.join(directory, "Photos"), {
    databasePath: path.join(directory, "state.sqlite"),
    pairingToken: "benchmark",
  });
  let server;
  try {
    await app.locals.ready;
    server = await new Promise((resolve) => {
      const s = app.listen(0, "127.0.0.1", () => resolve(s));
    });
    const base = `http://127.0.0.1:${server.address().port}`;
    let credential;
    async function request(route, method, body, offset) {
      const binary = Buffer.isBuffer(body);
      const response = await fetch(base + route, {
        method,
        headers: {
          "Content-Type": binary
            ? "application/octet-stream"
            : "application/json",
          ...(credential ? { Authorization: `Bearer ${credential}` } : {}),
          ...(offset === undefined ? {} : { "Upload-Offset": String(offset) }),
        },
        body: binary ? body : JSON.stringify(body),
      });
      const data = await response.json();
      if (!response.ok) throw new Error(`${route}: ${data.error}`);
      return data;
    }
    const paired = await request("/pair", "POST", {
      pairingCode: "benchmark",
      clientId: crypto.randomUUID(),
      deviceName: "Synthetic fixture",
    });
    credential = paired.credential;
    const files = Array.from({ length: 36 }, (_, i) => {
      const bytes = Buffer.alloc(i < 32 ? 256 * 1024 : 8 * 1024 * 1024, i);
      return {
        bytes,
        uploadId: crypto.randomUUID(),
        fileName: `${i}.${i < 32 ? "jpg" : "mp4"}`,
      };
    });
    const hashStart = performance.now();
    for (const file of files)
      file.hash = crypto.createHash("sha256").update(file.bytes).digest("hex");
    const hashMs = performance.now() - hashStart;
    const jobId = crypto.randomUUID(),
      totalBytes = files.reduce((sum, f) => sum + f.bytes.length, 0);
    await request(`/v2/jobs/${jobId}`, "PUT", {
      state: "running",
      totalFiles: files.length,
      totalBytes,
    });
    let index = 0;
    const started = performance.now();
    await Promise.all(
      Array.from({ length: parallel }, async () => {
        while (index < files.length) {
          const file = files[index++];
          await request("/v2/uploads", "POST", {
            uploadId: file.uploadId,
            jobId,
            hash: file.hash,
            hashAlgorithm: "sha256",
            size: file.bytes.length,
            fileName: file.fileName,
            bucketName: "Fixture",
          });
          for (
            let offset = 0;
            offset < file.bytes.length;
            offset += 4 * 1024 * 1024
          )
            await request(
              `/v2/uploads/${file.uploadId}`,
              "PATCH",
              file.bytes.subarray(offset, offset + 4 * 1024 * 1024),
              offset,
            );
          await request(`/v2/uploads/${file.uploadId}/complete`, "POST", {});
        }
      }),
    );
    const elapsedMs = performance.now() - started;
    await request(`/v2/jobs/${jobId}`, "PUT", {
      state: "completed",
      totalFiles: files.length,
      totalBytes,
      completedFiles: files.length,
      completedBytes: totalBytes,
    });
    return {
      parallel,
      files: files.length,
      bytes: totalBytes,
      hashMs: Math.round(hashMs),
      receiverMs: Math.round(elapsedMs),
      loopbackMiBPerSecond: +(
        totalBytes /
        1024 ** 2 /
        (elapsedMs / 1000)
      ).toFixed(1),
    };
  } finally {
    if (server) {
      server.closeAllConnections();
      await new Promise((resolve) => server.close(resolve));
    }
    app.locals.close();
    const checked = path.resolve(directory);
    if (
      !checked.startsWith(path.resolve(os.tmpdir()) + path.sep) ||
      !path.basename(checked).startsWith("pherry-benchmark-")
    )
      throw new Error("Unexpected benchmark directory");
    fs.rmSync(checked, { recursive: true, force: true });
  }
}
(async () => {
  console.log(
    "Synthetic loopback + disk results; these are not phone or Wi-Fi speeds.",
  );
  for (const parallel of [2, 4, 6])
    console.log(JSON.stringify(await measure(parallel)));
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
