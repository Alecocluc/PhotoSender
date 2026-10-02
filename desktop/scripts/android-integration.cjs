const path = require('node:path');
const fs = require('node:fs');
const { spawn, spawnSync } = require('node:child_process');
const { startFixture } = require('./integration-receiver.cjs');

function emulatorTarget() {
  const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT ||
    (process.platform === 'win32' && process.env.LOCALAPPDATA ? path.join(process.env.LOCALAPPDATA, 'Android', 'Sdk') : '');
  const sdkAdb = sdk && path.join(sdk, 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb');
  const adb = process.env.PHERRY_ADB || (sdkAdb && fs.existsSync(sdkAdb) ? sdkAdb : 'adb');
  const result = spawnSync(adb, ['devices'], { encoding: 'utf8' });
  if (result.error || result.status !== 0) throw new Error(`Could not list emulators: ${result.error?.message || result.stderr}`);
  const connected = result.stdout.split(/\r?\n/).map(line => line.trim().split(/\s+/))
    .filter(([serial, state]) => /^emulator-\d+$/.test(serial) && state === 'device').map(([serial]) => serial);
  const requested = process.env.PHERRY_TEST_EMULATOR || process.env.ANDROID_SERIAL;
  if (requested && !connected.includes(requested)) throw new Error('Choose a running emulator with PHERRY_TEST_EMULATOR; this fixture does not target physical phones.');
  if (!requested && connected.length !== 1) throw new Error('Start one Android emulator, or choose one with PHERRY_TEST_EMULATOR.');
  return { serial: requested || connected[0], adb };
}

function verifyReports(startedAt) {
  const directory = path.resolve(__dirname, '../../app/build/outputs/androidTest-results/connected/debug');
  const reports = fs.readdirSync(directory).filter(name => /^TEST-.*\.xml$/.test(name))
    .map(name => path.join(directory, name)).filter(file => fs.statSync(file).mtimeMs >= startedAt - 2000)
    .map(file => fs.readFileSync(file, 'utf8')).join('\n');
  for (const name of ['com.appharbor.pherry.data.network.TransferApiReceiverTest']) {
    const matches = reports.match(new RegExp(`classname="${name.replaceAll('.', '\\.')}"`, 'g')) || [];
    if (matches.length < 2) throw new Error(`Instrumentation did not run both tests in ${name}; refusing an incomplete success.`);
  }
  if (/<testsuite\b[^>]*(?:failures|errors|skipped)="[1-9]/.test(reports))
    throw new Error('Instrumentation reported failed or skipped tests.');
  console.log('Verified real Kotlin/Node protocol test reports.');
}

async function main() {
  const { serial, adb } = emulatorTarget();
  const port = Number(process.env.PHERRY_INTEGRATION_PORT || 0);
  const fixture = await startFixture(port);
  let reversed = false;
  const reverse = (...args) => {
    const result = spawnSync(adb, ['-s', serial, 'reverse', ...args], { encoding: 'utf8' });
    if (result.error || result.status !== 0) throw new Error(`Emulator port forwarding failed: ${result.error?.message || result.stderr}`);
  };
  try {
    reverse('--no-rebind', `tcp:${fixture.port}`, `tcp:${fixture.port}`);
    reversed = true;
    const args = [':app:connectedDebugAndroidTest',
      '-Pandroid.testInstrumentationRunnerArguments.package=com.appharbor.pherry.data',
      `-Pandroid.testInstrumentationRunnerArguments.pherryReceiver=http://127.0.0.1:${fixture.port}`];
    const windows = process.platform === 'win32';
    const startedAt = Date.now();
    // Only fixed Gradle task names and a numeric fixture port enter the Windows shell.
    const child = spawn(windows ? 'cmd.exe' : './gradlew', windows ? ['/d', '/c', '.\\gradlew.bat', ...args] : args,
      { cwd: path.resolve(__dirname, '../..'), stdio: 'inherit', env: { ...process.env, ANDROID_SERIAL: serial } });
    process.exitCode = await new Promise((resolve, reject) => {
      child.once('error', reject);
      child.once('exit', code => resolve(code ?? 1));
    });
    if (process.exitCode === 0) verifyReports(startedAt);
  } finally {
    try { if (reversed) reverse('--remove', `tcp:${fixture.port}`); }
    finally { await fixture.stop(); }
  }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
