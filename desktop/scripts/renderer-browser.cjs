// Browser integration checks. Run with PHERRY_PLAYWRIGHT_MODULE and PHERRY_CHROMIUM set,
// or install Playwright in the development environment. No receiver or user files are touched.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const { chromium } = require(process.env.PHERRY_PLAYWRIGHT_MODULE || 'playwright');
const root = path.resolve(__dirname, '../renderer');
const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.ttf': 'font/ttf' };
const server = http.createServer((req, res) => {
  const target = path.resolve(root, '.' + decodeURIComponent(req.url.split('?')[0] === '/' ? '/index.html' : req.url.split('?')[0]));
  if (!target.startsWith(root + path.sep)) { res.writeHead(403); return res.end(); }
  try { res.writeHead(200, { 'Content-Type': types[path.extname(target)] || 'application/octet-stream' }); res.end(fs.readFileSync(target)); }
  catch { res.writeHead(404); res.end(); }
});
(async () => {
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const browser = await chromium.launch({ headless: true, ...(process.env.PHERRY_CHROMIUM ? { executablePath: process.env.PHERRY_CHROMIUM } : {}) });
  try {
    const page = await browser.newPage({ viewport: { width: 1280, height: 820 }, locale: 'en-US' });
    const errors = [];
    const artifacts = process.env.PHERRY_RENDERER_ARTIFACTS;
    if (artifacts) fs.mkdirSync(artifacts, { recursive: true });
    page.on('pageerror', (error) => { errors.push(error.message); console.error('Renderer error:', error.message); });
    await page.addInitScript(() => {
      const stamp = Date.now();
      const devices = [{ deviceId: 'pixel', deviceName: 'Pixel', deviceFolder: 'Pixel-123', pairedAt: stamp, lastSeenAt: stamp },
        { deviceId: 'samsung', deviceName: 'Samsung', deviceFolder: 'Samsung-456', pairedAt: stamp, lastSeenAt: stamp }];
      const files = Array.from({ length: 20000 }, (_, i) => ({ id: i + 1, relativePath: `${i % 2 ? 'Pixel-123' : 'Samsung-456'}/Camera/IMG_${i}.${i % 5 ? 'jpg' : 'mp4'}`,
        fileName: `IMG_${i}.${i % 5 ? 'jpg' : 'mp4'}`, originalName: `IMG_${i}.${i % 5 ? 'jpg' : 'mp4'}`,
        bucketName: 'Camera', deviceId: i % 2 ? 'pixel' : 'samsung', deviceName: i % 2 ? 'Pixel' : 'Samsung',
        kind: i % 5 ? 'photo' : 'video', timestamp: stamp - i * 60000, size: 4000000 }));
      const settings = { downloadPath: 'C:/Pictures/Pherry', pairingToken: 'abcdef', port: 3210, theme: 'light', notifyOnArrival: true, minimizeToTray: true };
      window.fixture = { jobs: [], streams: [], thumbs: 0, activeThumbs: 0, maxThumbs: 0, opened: [], queries: [], files, devices, hooks: {} };
      const f = window.fixture;
      f.visible = (visible) => { f.hidden = !visible; document.dispatchEvent(new Event('visibilitychange')); };
      Object.defineProperty(document, 'hidden', { configurable: true, get: () => !!f.hidden });
      f.arrive = () => {
        const id = 20000 + (f.arrivals = (f.arrivals || 0) + 1);
        const entry = { ...files[1], id, fileName: `arrival_${id}.jpg`, originalName: `arrival_${id}.jpg`,
          relativePath: `Pixel-123/Camera/arrival_${id}.jpg`, timestamp: stamp + id, deviceId: 'pixel', deviceName: 'Pixel' };
        files.unshift(entry); f.hooks.arrival(entry); return entry;
      };
      const query = async (opts = {}, history = false) => {
        window.fixture.queries.push({ ...opts, history });
        await new Promise((resolve) => setTimeout(resolve, 100));
        let rows = history ? files.slice(0, 5000) : files;
        const snapshot = opts.snapshot ?? 20000 + (f.arrivals || 0);
        rows = rows.filter((row) => row.id <= snapshot);
        if (opts.query) rows = rows.filter((row) => (row.fileName + ' ' + row.bucketName + ' ' + row.deviceName).toLowerCase().includes(opts.query.toLowerCase()));
        if (opts.deviceId) rows = rows.filter((row) => row.deviceId === opts.deviceId);
        if (opts.album) rows = rows.filter((row) => row.bucketName === opts.album);
        if (opts.kind && opts.kind !== 'all') rows = rows.filter((row) => row.kind === opts.kind);
        if (opts.sort === 'oldest') rows = [...rows].reverse();
        const offset = opts.offset || 0, limit = opts.limit || 100;
        return { items: rows.slice(offset, offset + limit), totalCount: rows.length, nextOffset: offset + limit,
          hasMore: offset + limit < rows.length, snapshot, albums: ['Camera'], devices, historyCap: history ? 5000 : null };
      };
      const hook = (name) => (cb) => { window.fixture.hooks[name] = cb; };
      window.api = {
        getSettings: async () => settings, getServerState: async () => ({ running: true, port: 3210 }),
        getStatus: async () => ({ mediaCount: 20000, mediaBytes: 80000000000, totalReceived: 20000, totalBytes: 80000000000,
          jobs: window.fixture.jobs, receiving: window.fixture.streams, devices, freeBytes: 900000000000, libraryId: 'fixture', recentActivity: files.slice(0, 50) }),
        getHistory: (opts) => query(opts, true), getMedia: (opts) => query(opts), getJobs: async () => ({ items: window.fixture.jobs }),
        getDevices: async () => ({ items: devices }), getLocalIPs: async () => ['192.168.1.42'],
        getHostInfo: async () => ({ hostname: 'ALEX-PC', platform: 'win32', version: '1.0.0' }),
        getThumbnail: async () => { const f = window.fixture; f.thumbs++; f.activeThumbs++; f.maxThumbs = Math.max(f.maxThumbs, f.activeThumbs); await new Promise((r) => setTimeout(r, 8)); f.activeThumbs--; return 'data:image/svg+xml,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="32" height="24"><rect width="32" height="24" fill="#708c60"/></svg>'); },
        openFile: async (args) => { window.fixture.opened.push(args); return true; }, revealFile: async () => true, openFolder: async () => true,
        renameDevice: async ({ id, name }) => { devices.find((d) => d.deviceId === id).deviceName = name; return { success: true }; },
        revokeDevice: async ({ id }) => { devices.find((d) => d.deviceId === id).revoked = true; return { success: true }; },
        updateSettings: async (patch) => { Object.assign(settings, patch); return { success: true }; },
        rebuildHistoryProgress: async () => ({ success: true, running: false }), clearHistory: async () => ({ success: true }),
        exportHistory: async () => ({ success: true, filePath: 'history.json' }),
        onFileReceived: hook('arrival'), onFilesRemoved: hook('removed'), onServerState: hook('server'), onIpsChanged: hook('ips'),
        onJobsChanged: hook('jobs'), onDevicesChanged: hook('devices'),
      };
    });
    await page.goto(`http://127.0.0.1:${server.address().port}/`);
    await page.waitForFunction(() => document.querySelector('#receiver-title')?.textContent === 'Ready for your next backup');
    assert.equal(await page.locator('#pairing-panel').isVisible(), false);
    await page.click('[data-view="photos"]');
    await page.waitForFunction(() => document.querySelector('#browser-count')?.textContent === '20,000 files');
    await page.waitForSelector('.media-cell .frame');
    await page.waitForSelector('.media-cell .has-thumb img');
    const photoDOM = await page.evaluate(() => ({ all: document.querySelectorAll('*').length, frames: document.querySelectorAll('.media-cell .frame').length }));
    assert.ok(photoDOM.frames < 100); assert.ok(photoDOM.all < 2500);
    if (artifacts) { await page.waitForTimeout(180); await page.screenshot({ path: path.join(artifacts, 'photos-20k.png') }); }
    await page.locator('#browser-index').fill('19001'); await page.locator('#browser-jump button').click();
    await page.waitForSelector('[data-index="19000"] .frame');
    assert.equal(await page.evaluate(() => document.activeElement.closest('[data-index]')?.dataset.index), '19000');
    await page.keyboard.press('End');
    await page.waitForFunction(() => document.activeElement.closest('[data-index]')?.dataset.index === '19999');
    await page.keyboard.press('Home');
    await page.waitForFunction(() => document.activeElement.closest('[data-index]')?.dataset.index === '0');
    await page.evaluate(() => { document.activeElement.blur(); document.querySelector('#main').scrollTop = 0; });
    await page.waitForSelector('[data-index="0"] .has-thumb img');
    await page.evaluate(() => {
      const f = window.fixture;
      f.photoFrame = document.querySelector('[data-index="0"] .frame'); f.photoImage = f.photoFrame.querySelector('img');
      f.countChanges = [];
      f.countObserver = new MutationObserver(() => f.countChanges.push(document.querySelector('#browser-count').textContent));
      f.countObserver.observe(document.querySelector('#browser-count'), { childList: true });
      f.arrive();
    });
    await page.waitForFunction(() => document.querySelector('#browser-count')?.textContent === '20,001 files');
    assert.equal(await page.evaluate(() => {
      const f = window.fixture; return document.querySelector('[data-index="1"] .frame') === f.photoFrame && f.photoFrame.querySelector('img') === f.photoImage && f.photoImage.complete && f.photoImage.naturalWidth > 0;
    }), true, 'top arrivals must reuse the loaded photo and update its ordinal');
    assert.equal(await page.evaluate(() => window.fixture.countChanges.includes('Loading…')), false);
    await page.evaluate(() => window.fixture.countObserver.disconnect());
    await page.locator('[data-index="0"] .frame').focus();
    await page.evaluate(() => {
      const f = window.fixture;
      f.focusAtTop = document.activeElement; f.queriesAtTop = f.queries.length;
      f.hooks.arrival({ ...f.files[0], timestamp: Date.now() });
    });
    await page.waitForFunction(() => !document.querySelector('#browser-refresh').hidden);
    assert.equal(await page.evaluate(() => document.activeElement === window.fixture.focusAtTop && window.fixture.queries.length === window.fixture.queriesAtTop), true, 'a focused file at the top keeps its snapshot while new arrivals wait');
    await page.locator('#browser-index').fill('19001'); await page.locator('#browser-jump button').click();
    await page.waitForFunction(() => document.activeElement.closest('[data-index]')?.dataset.index === '19000');
    await page.keyboard.press('Enter');
    assert.ok((await page.evaluate(() => window.fixture.opened.at(-1))).relativePath);
    await page.evaluate(() => window.fixture.hooks.arrival({ relativePath: 'Pixel-123/Camera/new.jpg', fileName: 'new.jpg', deviceId: 'pixel', timestamp: Date.now() }));
    await page.waitForFunction(() => !document.querySelector('#browser-refresh').hidden);
    assert.equal(await page.evaluate(() => document.activeElement.closest('[data-index]')?.dataset.index), '19000');
    await page.evaluate(() => {
      const f = window.fixture;
      f.scrollBeforeHide = document.querySelector('#main').scrollTop; f.focusBeforeHide = document.activeElement;
      f.visible(false); f.arrive(); f.visible(true);
    });
    await page.waitForTimeout(400);
    assert.equal(await page.evaluate(() => document.querySelector('#main').scrollTop === window.fixture.scrollBeforeHide && document.activeElement === window.fixture.focusBeforeHide), true, 'restoring a hidden window must preserve the deep row and keyboard focus');
    await page.locator('#photos-query').fill('IMG_19999');
    await page.waitForFunction(() => document.querySelector('#browser-count')?.textContent === '1 matching file');
    assert.equal(await page.locator('.media-cell .frame').count(), 1);
    assert.equal(await page.locator('#photos-query').inputValue(), 'IMG_19999');
    await page.click('[data-view="history"]');
    await page.waitForFunction(() => document.querySelector('#browser-count')?.textContent === '5,000 transfers');
    const historyDOM = await page.evaluate(() => ({ all: document.querySelectorAll('*').length, rows: document.querySelectorAll('.inventory-row').length }));
    assert.ok(historyDOM.rows < 30); assert.ok(historyDOM.all < 2000);
    await page.waitForSelector('[data-index="0"] .has-thumb img');
    await page.evaluate(() => {
      const f = window.fixture;
      f.historyImage = document.querySelector('[data-index="0"] img');
      f.countChanges = []; f.countObserver.observe(document.querySelector('#browser-count'), { childList: true }); f.arrive();
    });
    await page.waitForFunction(() => document.querySelector('[data-index="1"] img') === window.fixture.historyImage);
    assert.equal(await page.evaluate(() => window.fixture.countChanges.includes('Loading…')), false);
    await page.evaluate(() => window.fixture.countObserver.disconnect());
    await page.locator('[data-index="0"] .inventory-entry').focus();
    await page.keyboard.press('End');
    await page.waitForFunction(() => document.activeElement.closest('[data-index]')?.dataset.index === '4999');
    await page.keyboard.press('Home');
    await page.waitForFunction(() => document.activeElement.closest('[data-index]')?.dataset.index === '0');
    await page.locator('#history-deviceId').selectOption('pixel');
    await page.waitForFunction(() => document.querySelector('#browser-count')?.textContent === '2,501 matching transfers');
    await page.click('[data-view="settings"]');
    const input = page.locator('.device-row').first().locator('input');
    await input.fill('Family Pixel');
    await page.evaluate(() => window.fixture.hooks.devices());
    await page.waitForTimeout(160);
    assert.equal(await page.locator('.device-row').first().locator('input').inputValue(), 'Family Pixel');
    await page.locator('.device-row').first().locator('[data-device-save]').click();
    await page.waitForFunction(() => window.fixture.devices[0].deviceName === 'Family Pixel');
    await page.click('[data-view="receiver"]');
    await page.locator('#pair-toggle').click();
    await page.waitForSelector('#arrivals-sheet .has-thumb img');
    await page.evaluate(() => {
      const f = window.fixture;
      f.receiverFrame = document.querySelector('#arrivals-sheet .frame'); f.receiverImage = f.receiverFrame.querySelector('img');
      f.receiverFrame.focus(); f.arrive();
    });
    await page.waitForFunction(() => document.querySelector('#arrivals-sheet .frame:nth-child(2)') === window.fixture.receiverFrame);
    assert.equal(await page.evaluate(() => window.fixture.receiverFrame.querySelector('img') === window.fixture.receiverImage && document.activeElement === window.fixture.receiverFrame), true, 'receiver arrivals must keep the loaded image and focused frame');
    await page.evaluate(() => {
      window.fixture.jobs = [
        { id: 'old', deviceId: 'pixel', deviceName: 'Family Pixel', state: 'completed', totalFiles: 10, completedFiles: 10, completedBytes: 1000, totalBytes: 1000, completedAt: Date.now() - 60000 },
        { id: 'new', deviceId: 'samsung', deviceName: 'Samsung', state: 'running', totalFiles: 20000, completedFiles: 0, completedBytes: 0, totalBytes: 80000000000 },
      ];
      window.fixture.streams = [{ jobId: 'new', device: 'Samsung', deviceId: 'samsung', fileName: 'first-big-video.mp4', total: 4000000000, bytes: 1000000 }];
      window.fixture.hooks.jobs();
    });
    await page.waitForFunction(() => document.querySelector('#receiver-title')?.textContent.includes('Samsung'));
    assert.equal(await page.locator('.receiver-job').count(), 1);
    assert.ok((await page.locator('.receiver-job').innerText()).includes('0 of 20,000 saved'));
    assert.equal(await page.locator('.receipt').count(), 1);
    assert.equal(await page.locator('.ticket').evaluate((el) => el.classList.contains('is-quiet')), true, 'an already open pairing ticket must lose yellow when a job starts');
    assert.equal(await page.locator('#pairing-panel').isVisible(), true);
    if (artifacts) { await page.waitForTimeout(480); await page.screenshot({ path: path.join(artifacts, 'receiver-live.png') }); }
    for (const view of ['receiver', 'photos', 'history', 'settings']) {
      await page.setViewportSize({ width: 480, height: 560 });
      await page.click('[data-view="' + view + '"]'); await page.waitForTimeout(180);
      const overflow = await page.evaluate(() => {
        const root = document.querySelector('#view-root'); return root.scrollWidth > root.clientWidth + 1;
      });
      assert.equal(overflow, false, view + ' must fit a narrow window');
      if (artifacts) await page.screenshot({ path: path.join(artifacts, view + '-480.png') });
    }
    assert.ok(await page.evaluate(() => window.fixture.maxThumbs <= 3));
    assert.deepEqual(errors, []);
    console.log(JSON.stringify({ photos: photoDOM, history: historyDOM, maxConcurrentThumbnails: await page.evaluate(() => window.fixture.maxThumbs), errors, checks: '20k virtualization, loaded-image arrival reuse, no loading announcements on arrivals, Home/End on both lists, foreground scroll/focus retention, quiet pairing during jobs, global search/filter, rename draft, 480px layout' }));
  } finally { await browser.close(); await new Promise((resolve) => server.close(resolve)); }
})().catch((error) => { console.error(error); process.exitCode = 1; server.close(); });
