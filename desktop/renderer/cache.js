/** Small byte-bounded LRU shared by the thumbnail pipeline and its isolated tests. */
export class ByteLRU {
  constructor(budget) { this.budget = budget; this.bytes = 0; this.entries = new Map(); }
  has(key) { return this.entries.has(key); }
  get(key) {
    const item = this.entries.get(key);
    if (!item) return undefined;
    this.entries.delete(key); this.entries.set(key, item);
    return item.value;
  }
  set(key, value, bytes = typeof value === 'string' ? value.length * 2 : 128) {
    this.delete(key);
    if (bytes > this.budget) return;
    this.entries.set(key, { value, bytes }); this.bytes += bytes;
    while (this.bytes > this.budget) this.delete(this.entries.keys().next().value);
  }
  delete(key) {
    const item = this.entries.get(key);
    if (item) { this.bytes -= item.bytes; this.entries.delete(key); }
  }
  clear() { this.entries.clear(); this.bytes = 0; }
}
