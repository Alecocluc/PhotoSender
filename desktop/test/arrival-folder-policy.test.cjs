const test = require("node:test");
const assert = require("node:assert/strict");
const { ArrivalFolderPolicy } = require("../arrival-folder-policy");

test("a 20,000-file backup opens its first arrival folder once", () => {
  const policy = new ArrivalFolderPolicy();
  let openings = 0;
  for (let i = 0; i < 20000; i++)
    if (policy.shouldOpen("backup-one", "/Photos", `/Photos/Album${i % 20}`, i)) openings++;
  assert.equal(openings, 1);
  assert.equal(policy.shouldOpen("backup-two", "/Photos", "/Photos/Album0"), true);
  assert.equal(policy.shouldOpen("backup-one", "/OtherDestination", "/OtherDestination/Album0"), true);
});

test("legacy arrivals are limited per folder and bookkeeping stays bounded", () => {
  const policy = new ArrivalFolderPolicy({ limit: 3, legacyCooldownMs: 30000 });
  assert.equal(policy.shouldOpen(null, "/Photos", "/Photos/Album", 0), true);
  assert.equal(policy.shouldOpen(null, "/Photos", "/Photos/Album", 29999), false);
  assert.equal(policy.shouldOpen(null, "/Photos", "/Photos/Album", 30000), true);
  for (let i = 0; i < 100; i++) policy.shouldOpen(`job-${i}`, "/Photos", "/Photos/Album");
  assert.equal(policy.opened.size, 3);
});
