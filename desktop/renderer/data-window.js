/** A bounded page cache. Query generations discard late responses after filters change. */
export class DataWindow {
  constructor(fetchPage, changed, { pageSize = 120, maxPages = 6 } = {}) {
    this.fetchPage = fetchPage; this.changed = changed;
    this.pageSize = pageSize; this.maxPages = maxPages;
    this.generation = 0; this.reset({}, false);
  }
  reset(query, notify = true) {
    this.generation += 1; this.query = { ...query }; this.snapshot = undefined;
    this.pages = new Map(); this.pending = new Map(); this.errors = new Map();
    this.total = 0; this.ready = false; this.meta = {};
    if (notify) this.changed();
  }
  item(index) {
    const page = Math.floor(index / this.pageSize);
    const items = this.pages.get(page);
    if (!items) return undefined;
    this.pages.delete(page); this.pages.set(page, items);
    return items[index % this.pageSize];
  }
  async load(page = 0) {
    if (this.pages.has(page) || this.pending.has(page) || this.errors.has(page)) return;
    const generation = this.generation;
    const token = {};
    this.pending.set(page, token);
    try {
      const result = await this.fetchPage({ ...this.query, limit: this.pageSize,
        offset: page * this.pageSize, ...(this.snapshot === undefined ? {} : { snapshot: this.snapshot }) });
      if (generation !== this.generation) return;
      if (!result || result.success === false || !Array.isArray(result.items)) throw new Error(result?.error || 'The receiver did not return a list.');
      this.pages.set(page, result.items);
      while (this.pages.size > this.maxPages) this.pages.delete(this.pages.keys().next().value);
      this.total = Math.max(0, Number(result.totalCount) || 0);
      this.meta = result; this.ready = true;
      if (this.snapshot === undefined && result.snapshot != null) this.snapshot = result.snapshot;
    } catch (error) {
      if (generation === this.generation) this.errors.set(page, error.message || 'Could not load files.');
    } finally {
      if (this.pending.get(page) === token) this.pending.delete(page);
      if (generation === this.generation) this.changed();
    }
  }
  request(start, end) {
    const first = Math.floor(start / this.pageSize), last = Math.floor(Math.max(start, end - 1) / this.pageSize);
    for (let page = first; page <= last; page++) this.load(page);
  }
  retry() { this.errors.clear(); this.changed(); }
  dispose() { this.generation += 1; this.pages.clear(); this.pending.clear(); }
}

export function visibleRange({ total, columns, rowHeight, scrollTop, viewportHeight, overscan = 2 }) {
  const rows = Math.ceil(total / columns);
  const first = Math.max(0, Math.min(rows, Math.floor(Math.max(0, scrollTop) / rowHeight) - overscan));
  const last = Math.min(rows, Math.ceil((Math.max(0, scrollTop) + viewportHeight) / rowHeight) + overscan);
  return { start: first * columns, end: Math.min(total, last * columns), top: first * rowHeight, height: rows * rowHeight };
}
