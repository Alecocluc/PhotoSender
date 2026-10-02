/** Bounded exponential retry; a stable minute resets the crash budget. */
class ReceiverRestartPolicy {
  constructor() { this.attempts = 0; }
  reset() { this.attempts = 0; }
  nextDelay(uptimeMs = 0) {
    if (uptimeMs >= 60000) this.reset();
    if (this.attempts >= 5) return null;
    return Math.min(30000, 1000 * 2 ** this.attempts++);
  }
}
module.exports = { ReceiverRestartPolicy };
