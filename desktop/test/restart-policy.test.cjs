const { test } = require('node:test');
const assert = require('node:assert/strict');
const { ReceiverRestartPolicy } = require('../restart-policy');
test('receiver restart retries are bounded and a stable run resets the budget', () => {
  const policy = new ReceiverRestartPolicy();
  assert.deepEqual(Array.from({ length: 6 }, () => policy.nextDelay(100)), [1000, 2000, 4000, 8000, 16000, null]);
  assert.equal(policy.nextDelay(60000), 1000);
  policy.reset();
  assert.equal(policy.nextDelay(), 1000);
});
