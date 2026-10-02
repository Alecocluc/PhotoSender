// A backup can contain thousands of files. Opening Explorer for every arrival
// competes with the receiver for disk/CPU and continually steals focus.
class ArrivalFolderPolicy {
  constructor({ limit = 256, legacyCooldownMs = 30000 } = {}) {
    this.limit = limit;
    this.legacyCooldownMs = legacyCooldownMs;
    this.opened = new Map();
  }

  shouldOpen(jobId, root, folder, now = Date.now()) {
    const key = jobId ? `job:${root}\0${jobId}` : `folder:${folder}`;
    const last = this.opened.get(key);
    // Refresh insertion order even for suppressed arrivals, so a long active
    // backup is not evicted by a stream of other jobs.
    this.opened.delete(key);
    const allowed = last === undefined || (!jobId && now - last >= this.legacyCooldownMs);
    this.opened.set(key, allowed ? now : last);
    while (this.opened.size > this.limit)
      this.opened.delete(this.opened.keys().next().value);
    return allowed;
  }
}

module.exports = { ArrivalFolderPolicy };
