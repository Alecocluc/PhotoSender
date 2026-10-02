// Synthetic loopback + disk results include client and receiver in one process.
// These measure neither phone storage nor Wi-Fi throughput.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const crypto = require("node:crypto");
const { performance, monitorEventLoopDelay } = require("node:perf_hooks");
const { createServer } = require("../receiver");
const MiB = 1024 ** 2;
const profiles = {
  smoke: { count: 36, size: (i) => i < 32 ? MiB / 4 : 8 * MiB },
  small: { count: 500, size: () => 3 * MiB },
  large: { count: 2, size: () => 1024 * MiB },
  many: { count: 20000, size: () => 4096 },
};
function options(args) {
  const result = { profile: "smoke", parallel: [2, 3, 4, 6], chunkBytes: 4 * MiB };
  for (let i = 0; i < args.length; i++) {
    const key = args[i];
    if (key === "--help") return { help: true };
    const value = args[++i];
    if (!value) throw new Error("Missing value for " + key);
    if (key === "--profile") result.profile = value;
    else if (key === "--parallel") result.parallel = value.split(",").map(Number);
    else if (key === "--files") result.files = Number(value);
    else if (key === "--size-mib") result.size = Number(value) * MiB;
    else if (key === "--chunk-mib") result.chunkBytes = Number(value) * MiB;
    else throw new Error("Unknown option: " + key);
  }
  if (!profiles[result.profile]) throw new Error("Unknown profile: " + result.profile);
  for (const [key, value, min, max] of [
    ["files", result.files ?? profiles[result.profile].count, 1, 100000],
    ["size", result.size ?? 4, 4, 16 * 1024 * MiB],
    ["chunkBytes", result.chunkBytes, 4096, 4 * MiB],
    ...result.parallel.map((n) => ["parallel", n, 1, 32]),
  ]) if (!Number.isSafeInteger(value) || value < min || value > max)
    throw new Error("Invalid " + key + ": " + value);
  if (result.chunkBytes % 4096) throw new Error("Chunks must be a multiple of 4 KiB");
  return result;
}
// A distinct 4 KiB pattern per file; aligned chunks preserve content at any chunk size.
function fillPayload(buffer, index) {
  buffer.fill(index % 251);
  for (let offset = 0; offset + 4 <= buffer.length; offset += 4096)
    buffer.writeUInt32LE(index, offset);
  return buffer;
}
async function measure(config, parallel) {
  const profile = profiles[config.profile];
  const files = Array.from({ length: config.files ?? profile.count }, (_, index) => ({
    index, size: config.size ?? profile.size(index), uploadId: crypto.randomUUID(),
    fileName: index + ((config.size ?? profile.size(index)) >= 8 * MiB ? ".mp4" : ".jpg"),
  }));
  const totalBytes = files.reduce((sum, file) => sum + file.size, 0);
  const payloadBytes = Math.min(config.chunkBytes, files.reduce((max, file) => Math.max(max, file.size), 0));
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "pherry-benchmark-"));
  let app, server, sampling, jobEvents = 0, arrivals = 0;
  const delays = monitorEventLoopDelay({ resolution: 10 });
  let peakRss = process.memoryUsage().rss;
  const sampleMemory = () => { peakRss = Math.max(peakRss, process.memoryUsage().rss); };
  try {
    const free = fs.statfsSync(directory);
    if (Number(free.bavail) * Number(free.bsize) < totalBytes + 512 * MiB)
      throw new Error("Insufficient temporary disk space: need " + Math.ceil(totalBytes / MiB + 512) + " MiB");
    app = createServer(path.join(directory, "Photos"), {
      databasePath: path.join(directory, "state.sqlite"), pairingToken: "benchmark",
      onJobsChanged: () => { jobEvents++; }, onFileReceived: () => { arrivals++; },
    });
    await app.locals.ready;
    server = await new Promise((resolve) => {
      const s = app.listen(0, "127.0.0.1", () => resolve(s));
    });
    const base = "http://127.0.0.1:" + server.address().port;
    let credential;
    async function request(route, method = "GET", body, offset) {
      const binary = Buffer.isBuffer(body);
      const response = await fetch(base + route, {
        method, signal: AbortSignal.timeout(120000),
        headers: {
          "Content-Type": binary ? "application/octet-stream" : "application/json",
          ...(credential ? { Authorization: "Bearer " + credential } : {}),
          ...(offset === undefined ? {} : { "Upload-Offset": String(offset) }),
        },
        body: binary ? body : body === undefined ? undefined : JSON.stringify(body),
      });
      const data = await response.json();
      if (!response.ok) throw new Error(route + ": " + data.error);
      return data;
    }
    credential = (await request("/pair", "POST", {
      pairingCode: "benchmark", clientId: crypto.randomUUID(), deviceName: "Synthetic fixture",
    })).credential;
    // Never allocate a whole video or keep every photo's payload in memory.
    const hashStart = performance.now(), hashPayload = Buffer.allocUnsafe(payloadBytes);
    for (const file of files) {
      fillPayload(hashPayload, file.index);
      const hash = crypto.createHash("sha256");
      for (let offset = 0; offset < file.size; offset += hashPayload.length)
        hash.update(hashPayload.subarray(0, Math.min(hashPayload.length, file.size - offset)));
      file.hash = hash.digest("hex");
    }
    const hashMs = performance.now() - hashStart, jobId = crypto.randomUUID();
    await request("/v2/jobs/" + jobId, "PUT", { state: "running", totalFiles: files.length, totalBytes });
    let index = 0;
    const completedAt = [];
    sampling = setInterval(sampleMemory, 50);
    delays.enable();
    const started = performance.now();
    // Settle every worker before cleaning up, including after a failed request.
    const workers = await Promise.allSettled(Array.from({ length: parallel }, async () => {
      const payload = Buffer.allocUnsafe(payloadBytes);
      while (index < files.length) {
        const file = files[index++];
        fillPayload(payload, file.index);
        const upload = await request("/v2/uploads", "POST", {
          uploadId: file.uploadId, jobId, hash: file.hash, size: file.size,
          fileName: file.fileName, bucketName: "Fixture",
        });
        assert.equal(upload.complete, false, "Unique fixtures must actually upload");
        for (let offset = 0; offset < file.size; offset += payload.length) {
          const length = Math.min(payload.length, file.size - offset);
          const saved = await request("/v2/uploads/" + file.uploadId, "PATCH", payload.subarray(0, length), offset);
          assert.equal(saved.offset, offset + length, "Chunk acknowledgement offset");
        }
        const receipt = await request("/v2/uploads/" + file.uploadId + "/complete", "POST", {});
        assert.equal(receipt.hash, file.hash);
        assert.equal(receipt.size, file.size);
        assert.equal(receipt.complete, true);
        file.relativePath = receipt.relativePath;
        completedAt.push(performance.now() - started);
      }
    }));
    const failure = workers.find((worker) => worker.status === "rejected");
    if (failure) throw failure.reason;
    const elapsedMs = performance.now() - started;
    delays.disable(); sampleMemory();
    const finished = await request("/v2/jobs/" + jobId, "PUT", {
      state: "completed", totalFiles: files.length, totalBytes,
      completedFiles: files.length, completedBytes: totalBytes,
    });
    assert.equal(finished.savedFiles, files.length);
    assert.equal(finished.savedBytes, totalBytes);
    assert.equal(arrivals, files.length);
    // Independently read back the first/middle/last files after the timed upload.
    // This also checks the bytes actually written by the disk-buffering path.
    const sampleIndices = new Set([0, Math.floor(files.length / 2), files.length - 1]);
    const verifyStart = performance.now();
    for (const i of sampleIndices) {
      const file = files[i], hash = crypto.createHash("sha256");
      const root = path.join(directory, "Photos"), full = path.resolve(root, file.relativePath);
      assert.ok(full.startsWith(root + path.sep));
      for await (const bytes of fs.createReadStream(full)) hash.update(bytes);
      assert.equal(hash.digest("hex"), file.hash, "Saved file checksum");
    }
    const verifyMs = performance.now() - verifyStart;
    // Repeat-backup presence checks and final-page browsing exercise the full inventory.
    const lookupStart = performance.now();
    for (let offset = 0; offset < files.length; offset += 500) {
      const batch = files.slice(offset, offset + 500);
      const result = await request("/v2/files/exists", "POST", { hashes: batch.map((f) => f.hash) });
      assert.equal(result.files.length, batch.length);
      assert.ok(result.files.every((file, i) => file.exists && file.hash === batch[i].hash));
    }
    const lookupMs = performance.now() - lookupStart, browseStart = performance.now();
    const page = await request("/v2/media?limit=100&offset=" + Math.max(0, files.length - 100));
    assert.equal(page.totalCount, files.length);
    assert.equal(page.totalBytes, totalBytes);
    assert.equal(page.items.length, Math.min(100, files.length));
    const browseMs = performance.now() - browseStart, statusStart = performance.now();
    const status = await request("/status");
    assert.equal(status.mediaCount, files.length);
    const statusMs = performance.now() - statusStart, quartile = Math.max(1, Math.floor(files.length / 4));
    return {
      profile: config.profile, parallel, files: files.length, bytes: totalBytes, chunkMiB: config.chunkBytes / MiB,
      hashMs: Math.round(hashMs), receiverMs: Math.round(elapsedMs),
      loopbackMiBPerSecond: +(totalBytes / MiB / (elapsedMs / 1000)).toFixed(1),
      filesPerSecond: +(files.length / (elapsedMs / 1000)).toFixed(1),
      firstQuartileMs: Math.round(completedAt[quartile - 1]),
      lastQuartileMs: Math.round(completedAt.at(-1) - (completedAt[files.length - quartile - 1] || 0)),
      lookupMs: Math.round(lookupMs), browseMs: Math.round(browseMs), statusMs: Math.round(statusMs),
      sampledChecksums: sampleIndices.size, verifyMs: Math.round(verifyMs),
      jobEvents, peakCombinedRssMiB: Math.round(peakRss / MiB),
      eventLoopP99Ms: +(delays.percentile(99) / 1e6).toFixed(1),
    };
  } finally {
    clearInterval(sampling); delays.disable();
    if (server) {
      server.closeAllConnections();
      await new Promise((resolve) => server.close(resolve));
    }
    app?.locals.close();
    const checked = path.resolve(directory);
    if (!checked.startsWith(path.resolve(os.tmpdir()) + path.sep) || !path.basename(checked).startsWith("pherry-benchmark-"))
      throw new Error("Unexpected benchmark directory");
    fs.rmSync(checked, { recursive: true, force: true });
  }
}
(async () => {
  const config = options(process.argv.slice(2));
  if (config.help) {
    console.log([
      "Usage: npm run benchmark -- [options]",
      "  --profile smoke|small|large|many   36 mixed / 500 x 3 MiB / 2 x 1 GiB / 20,000 x 4 KiB",
      "  --parallel 2,3,4,6                Worker counts to compare (default: 2,3,4,6)",
      "  --files N                        Override the profile's file count",
      "  --size-mib N                     Override file sizes (fractions allowed)",
      "  --chunk-mib N                    Chunk size, at most 4 MiB (default: 4)",
      "",
      "Each run checks receipts, counts, sample disk checksums and repeat-backup presence. Temporary fixtures are removed.",
      "The many profile measures file-count scaling, not a 20,000-photo Wi-Fi backup.",
      "Client and receiver share one process; throughput and RSS include both.",
    ].join("\n"));
    return;
  }
  console.log("Synthetic loopback + disk results; client and receiver share one process. Not phone or Wi-Fi speeds.");
  for (const parallel of config.parallel) console.log(JSON.stringify(await measure(config, parallel)));
})().catch((error) => { console.error(error); process.exitCode = 1; });
