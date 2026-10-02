// Real Electron/preload/utility-process integration. No installed user's settings or files are used.
// Requires desktop npm dependencies and Playwright (or PHERRY_PLAYWRIGHT_MODULE).
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const net = require('node:net');
const crypto = require('node:crypto');
const { _electron: electron } = require(process.env.PHERRY_PLAYWRIGHT_MODULE || 'playwright');
const desktop = path.resolve(__dirname, '..');

async function freePort() {
  const reservation = net.createServer();
  await new Promise((resolve, reject) => { reservation.once('error', reject); reservation.listen(0, '0.0.0.0', resolve); });
  const port = reservation.address().port;
  await new Promise((resolve, reject) => reservation.close((error) => error ? reject(error) : resolve()));
  return port;
}
async function until(label, read, timeout = 20000) {
  const deadline = Date.now() + timeout;
  let failure;
  do {
    try { const result = await read(); if (result) return result; } catch (error) { failure = error; }
    await new Promise((resolve) => setTimeout(resolve, 100));
  } while (Date.now() < deadline);
  throw new Error(`Timed out waiting for ${label}${failure ? ': ' + failure.message : ''}`);
}
async function run() {
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'pherry-electron-test-'));
  const userData = path.join(temporary, 'UserData'), originals = path.join(temporary, 'Photos');
  const port = await freePort(), pairingCode = 'abcdef', deviceId = crypto.randomUUID();
  fs.mkdirSync(userData); fs.mkdirSync(originals);
  fs.writeFileSync(path.join(userData, 'settings.json'), JSON.stringify({
    downloadPath: originals, port, pairingToken: pairingCode, deviceId, theme: 'light',
    launchAtStartup: false, minimizeToTray: false, notifyOnArrival: false, autoOpenFolder: false,
  }));
  const env = { ...process.env, PHERRY_TEST_USER_DATA: userData };
  delete env.ELECTRON_RUN_AS_NODE;
  let application;
  const stderr = [], rendererErrors = [];
  try {
    application = await electron.launch({
      executablePath: process.env.PHERRY_ELECTRON || require('electron'), args: [desktop],
      cwd: desktop, env, timeout: 30000,
    });
    application.process().stderr?.on('data', (chunk) => { stderr.push(String(chunk)); if (stderr.length > 40) stderr.shift(); });
    const page = await application.firstWindow({ timeout: 20000 });
    page.on('pageerror', (error) => rendererErrors.push(error.message));
    await page.waitForFunction(() => !!window.api?.getStatus);
    const actual = await application.evaluate(({ app, BrowserWindow }) => ({
      userData: app.getPath('userData'), visible: BrowserWindow.getAllWindows().some((window) => window.isVisible()),
    }));
    assert.equal(path.resolve(actual.userData), path.resolve(userData));
    assert.equal(actual.visible, false, 'isolated test must leave the application window hidden');
    const initial = await until('the isolated receiver', async () => {
      const status = await page.evaluate(() => window.api.getServerState()); return status.running && status.port === port && status;
    });
    const firstStatus = await page.evaluate(() => window.api.getStatus());
    assert.ok(firstStatus.libraryId); assert.equal(path.resolve(firstStatus.downloadPath), path.resolve(originals));
    assert.equal((await page.evaluate(() => window.api.getMedia({ limit: 10 }))).totalCount, 0);
    const base = `http://127.0.0.1:${port}`;
    let credential = '';
    async function request(route, method = 'GET', body, headers = {}) {
      const response = await fetch(base + route, {
        method, signal: AbortSignal.timeout(10000),
        headers: {
          ...(credential ? { Authorization: `Bearer ${credential}`, 'X-Pherry-Receiver': deviceId, 'X-Pherry-Library': firstStatus.libraryId } : {}),
          ...(body && !Buffer.isBuffer(body) ? { 'Content-Type': 'application/json' } : {}), ...headers,
        }, body: body === undefined ? undefined : Buffer.isBuffer(body) ? body : JSON.stringify(body),
      });
      const data = await response.json();
      assert.ok(response.ok, `${method} ${route}: ${response.status} ${data.error || ''}`);
      return data;
    }
    const clientId = crypto.randomUUID();
    const paired = await request('/pair', 'POST', { clientId, deviceName: 'Integration phone', pairingCode });
    credential = paired.credential;
    assert.equal(paired.libraryId, firstStatus.libraryId);
    const pairedDevices = await page.evaluate(() => window.api.getDevices());
    assert.ok(pairedDevices.items.some((phone) => phone.deviceId === clientId));
    const bytes = crypto.randomBytes(5 * 1024 * 1024 + 137), offset = 4 * 1024 * 1024;
    const jobId = crypto.randomUUID(), uploadId = crypto.randomUUID();
    await request(`/v2/jobs/${jobId}`, 'PUT', { state: 'running', totalFiles: 1, totalBytes: bytes.length });
    await request('/v2/uploads', 'POST', {
      jobId, uploadId, hash: crypto.createHash('sha256').update(bytes).digest('hex'),
      fileName: 'resume-fixture.jpg', bucketName: 'Camera', size: bytes.length,
    });
    const firstChunk = await request(`/v2/uploads/${uploadId}`, 'PATCH', bytes.subarray(0, offset), {
      'Content-Type': 'application/octet-stream', 'Upload-Offset': '0',
    });
    assert.equal(firstChunk.offset, offset);
    await page.evaluate(() => {
      window.integrationStates = []; window.integrationArrivals = [];
      window.api.onServerState((state) => window.integrationStates.push(state));
      window.api.onFileReceived((file) => window.integrationArrivals.push(file));
    });
    // Identify and kill only this isolated Electron application's named receiver child.
    // No process enumeration outside app.getAppMetrics(), process-name kill or parent termination.
    const killed = await application.evaluate(({ app }) => {
      const receivers = app.getAppMetrics().filter((metric) => metric.type === 'Utility' &&
        (metric.name === 'Pherry Receiver' || metric.serviceName === 'Pherry Receiver'));
      if (receivers.length !== 1 || receivers[0].pid === process.pid) throw new Error('Cannot uniquely identify the receiver child');
      const receiver = receivers[0];
      process.kill(receiver.pid, 'SIGKILL');
      return { pid: receiver.pid, creationTime: receiver.creationTime };
    });
    const recovered = await until('automatic receiver recovery', async () => {
      const states = await page.evaluate(() => window.integrationStates);
      const offline = states.findIndex((state) => state.running === false);
      return offline >= 0 && states.slice(offline + 1).some((state) => state.running === true) && states;
    });
    assert.ok(recovered.some((state) => state.recovering === true), 'preload must receive the automatic recovery state');
    const replacement = await application.evaluate(({ app }) => app.getAppMetrics()
      .find((metric) => metric.type === 'Utility' && (metric.name === 'Pherry Receiver' || metric.serviceName === 'Pherry Receiver')));
    assert.ok(replacement && (replacement.pid !== killed.pid || replacement.creationTime !== killed.creationTime));
    const after = await page.evaluate(() => window.api.getStatus());
    assert.equal(after.libraryId, firstStatus.libraryId);
    const resumed = await request(`/v2/uploads/${uploadId}`);
    assert.equal(resumed.offset, offset); assert.equal(resumed.complete, false);
    const lastChunk = await request(`/v2/uploads/${uploadId}`, 'PATCH', bytes.subarray(offset), {
      'Content-Type': 'application/octet-stream', 'Upload-Offset': String(offset),
    });
    assert.equal(lastChunk.offset, bytes.length);
    // Commit immediately after the final chunk: acknowledgement must include release of its lock.
    const receipt = await request(`/v2/uploads/${uploadId}/complete`, 'POST', {});
    assert.equal(receipt.complete, true);
    await request(`/v2/jobs/${jobId}`, 'PUT', {
      state: 'completed', totalFiles: 1, totalBytes: bytes.length, completedFiles: 1,
      completedBytes: bytes.length, skippedFiles: 0, failedFiles: 0,
    });
    const savedPath = path.resolve(originals, receipt.relativePath);
    assert.ok(savedPath.startsWith(path.resolve(originals) + path.sep));
    assert.deepEqual(fs.readFileSync(savedPath), bytes);
    const inventory = await page.evaluate(() => window.api.getMedia({ query: 'resume-fixture', limit: 10 }));
    assert.equal(inventory.totalCount, 1); assert.equal(inventory.items[0].relativePath, receipt.relativePath);
    const job = await page.evaluate((id) => window.api.getJob({ id }), jobId);
    assert.equal(job.state, 'completed'); assert.equal(job.completedFiles, 1);
    await until('the real preload arrival event', () => page.evaluate(() => window.integrationArrivals.length > 0));
    assert.deepEqual(rendererErrors, []);
    console.log(JSON.stringify({
      result: 'passed', chunkBytesRetained: offset, fileBytesVerified: bytes.length,
      receiverRestarted: true, libraryIdentityRetained: true,
      checks: 'real Electron preload, paired utility receiver, forced receiver-child crash, automatic restart, durable offset resume, immediate commit, exact saved bytes, indexed search and job receipt',
    }));
  } catch (error) {
    if (stderr.length) console.error('Isolated Electron diagnostics:\n' + stderr.join('').slice(-8000));
    throw error;
  } finally {
    if (application) await application.close();
    const checked = path.resolve(temporary);
    assert.ok(checked.startsWith(path.resolve(os.tmpdir()) + path.sep) && path.basename(checked).startsWith('pherry-electron-test-'));
    fs.rmSync(checked, { recursive: true, force: true, maxRetries: 10, retryDelay: 100 });
  }
}
run().catch((error) => { console.error(error); process.exitCode = 1; });
