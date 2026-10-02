const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const renderer = path.join(__dirname, '..', 'renderer');
const url = (source) => `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`;
const moduleFrom = (name) => import(url(fs.readFileSync(path.join(renderer, name), 'utf8')));
const deferred = () => { let resolve; const promise = new Promise((r) => { resolve = r; }); return { promise, resolve }; };

test('thumbnail LRU evicts by bytes, refreshes recency, and keeps accounting correct', async () => {
  const { ByteLRU } = await moduleFrom('cache.js');
  const cache = new ByteLRU(12);
  cache.set('a', 'aaa'); cache.set('b', 'bbb');
  assert.equal(cache.bytes, 12);
  cache.get('a'); cache.set('c', 'ccc');
  assert.equal(cache.has('b'), false); assert.equal(cache.has('a'), true);
  cache.set('a', 'a'); assert.equal(cache.bytes, 8);
  cache.set('too-big', 'x'.repeat(50)); assert.equal(cache.has('too-big'), false);
  cache.clear(); assert.equal(cache.bytes, 0); assert.equal(cache.entries.size, 0);
});

test('changing a query discards an older page which finishes later', async () => {
  const { DataWindow } = await moduleFrom('data-window.js');
  const first = deferred(), second = deferred();
  let request = 0;
  const data = new DataWindow(() => ++request === 1 ? first.promise : second.promise, () => {});
  data.reset({ query: 'before' }); const old = data.load(0);
  data.reset({ query: 'after' }); const current = data.load(0);
  second.resolve({ items: [{ fileName: 'after.jpg' }], totalCount: 1, snapshot: 20 }); await current;
  first.resolve({ items: [{ fileName: 'before.jpg' }], totalCount: 1000, snapshot: 10 }); await old;
  assert.equal(data.total, 1); assert.equal(data.item(0).fileName, 'after.jpg'); assert.equal(data.snapshot, 20);
});

test('20,000 item browsing sends the snapshot and retains only six pages', async () => {
  const { DataWindow } = await moduleFrom('data-window.js');
  const requests = [];
  const data = new DataWindow(async (opts) => {
    requests.push(opts);
    return { items: Array.from({ length: opts.limit }, (_, i) => ({ id: opts.offset + i })), totalCount: 20000, snapshot: 21000 };
  }, () => {});
  await data.load(0);
  for (let p = 1; p <= 20; p++) await data.load(p);
  assert.equal(data.pages.size, 6); assert.equal(data.total, 20000);
  assert.equal(requests[1].snapshot, 21000); assert.equal(data.item(2400).id, 2400);
});

test('arrival refresh keeps the loaded snapshot until the new page is ready', async () => {
  const { DataWindow } = await moduleFrom('data-window.js');
  const next = deferred(); let calls = 0;
  const data = new DataWindow(() => ++calls === 1 ? Promise.resolve({ items: [{ id: 1 }], totalCount: 1, snapshot: 1 }) : next.promise, () => {});
  await data.load(0);
  const refreshing = data.refresh();
  assert.equal(data.ready, true); assert.equal(data.item(0).id, 1); assert.equal(data.snapshot, 1);
  next.resolve({ items: [{ id: 2 }, { id: 1 }], totalCount: 2, snapshot: 2 });
  assert.equal(await refreshing, true);
  assert.equal(data.item(0).id, 2); assert.equal(data.item(1).id, 1); assert.equal(data.snapshot, 2);
});

test('scrolling or focusing while an arrival query is pending keeps the reading snapshot', async () => {
  const { DataWindow } = await moduleFrom('data-window.js');
  const next = deferred(); let calls = 0, atTop = true;
  const data = new DataWindow(() => ++calls === 1 ? Promise.resolve({ items: [{ id: 1 }], totalCount: 1000, snapshot: 1 }) : next.promise, () => {});
  await data.load(0);
  const refreshing = data.refresh(() => atTop); atTop = false;
  next.resolve({ items: [{ id: 2 }], totalCount: 1001, snapshot: 2 });
  assert.equal(await refreshing, false);
  assert.equal(data.total, 1000); assert.equal(data.item(0).id, 1); assert.equal(data.snapshot, 1);
});

test('virtual window bounds rendered rows even at the end of a huge library', async () => {
  const { visibleRange } = await moduleFrom('data-window.js');
  for (const columns of [1, 2, 6]) {
    const result = visibleRange({ total: 20000, columns, rowHeight: 160, scrollTop: 200000, viewportHeight: 820 });
    assert.ok(result.end - result.start <= 11 * columns);
    assert.ok(result.start >= 0); assert.ok(result.end <= 20000);
  }
});

test('live arrivals respect history cap and detect real streams without idle heuristics', async () => {
  const utils = url(fs.readFileSync(path.join(renderer, 'utils.js'), 'utf8'));
  const source = fs.readFileSync(path.join(renderer, 'state.js'), 'utf8').replace("'./utils.js'", JSON.stringify(utils));
  const { state, noteArrival, sessionActive, sendingName } = await import(url(source));
  state.history = { items: [], totalCount: 5000, historyCap: 5000 };
  for (let i = 0; i < 20000; i++) noteArrival({ deviceId: 'phone', relativePath: `phone/${i}.jpg`, timestamp: i });
  assert.equal(state.history.totalCount, 5000); assert.equal(state.history.items.length, 100);
  state.jobs = [{ id: 'old', deviceName: 'Old phone', state: 'completed' }];
  state.status = { receiving: [{ device: 'New phone', fileName: 'first-big-video.mp4' }] };
  assert.equal(sessionActive(), true); assert.equal(sendingName(), 'New phone');
  state.status.receiving = []; assert.equal(sessionActive(), false);
});
