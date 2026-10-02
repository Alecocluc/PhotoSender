const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { createServer } = require('../receiver');

// An isolated real receiver for Android's instrumented protocol tests.
async function startFixture(port = 43210) {
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'pherry-integration-'));
  let app, server;
  const stop = async () => {
    if (server) {
      server.closeAllConnections();
      await new Promise(resolve => server.close(resolve));
      server = null;
    }
    app?.locals.close(); app = null;
    const resolved = path.resolve(temporary), parent = path.resolve(os.tmpdir());
    if (path.dirname(resolved) !== parent || !path.basename(resolved).startsWith('pherry-integration-'))
      throw new Error('Refusing to remove an unexpected fixture directory');
    fs.rmSync(resolved, { recursive: true, force: true });
  };
  try {
    app = createServer(path.join(temporary, 'Photos'), {
      databasePath: path.join(temporary, 'receiver.sqlite'),
      deviceId: 'integration-desktop', pairingToken: 'integration-code',
    });
    await app.locals.ready;
    server = await new Promise((resolve, reject) => {
      const listening = app.listen(port, '127.0.0.1', () => resolve(listening));
      listening.once('error', reject);
    });
    return { port: server.address().port, stop };
  } catch (error) { await stop(); throw error; }
}

if (require.main === module) {
  startFixture(Number(process.env.PHERRY_INTEGRATION_PORT || 43210)).then(fixture => {
    console.log(`Pherry integration receiver ready on ${fixture.port}`);
    let stopping = false;
    const stop = async () => {
      if (stopping) return;
      stopping = true;
      await fixture.stop();
      process.exit(0);
    };
    process.on('SIGINT', stop); process.on('SIGTERM', stop);
  }).catch(error => { console.error(error); process.exitCode = 1; });
}
module.exports = { startFixture };
