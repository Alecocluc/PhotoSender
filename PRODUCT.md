# Product

<!-- impeccable:product-schema 1 -->

## Platform

Android is native Jetpack Compose with Material 3. Pherry Desktop is Electron with plain HTML, CSS and ES modules. Both use the same lab envelope and contact sheet identity, with controls and navigation appropriate to each platform.

## Users and purpose

People with an Android phone and a computer on the same local network who want original photos and videos in a folder they own, without a cloud account or cable.

The primary workload is an occasional backup of a large camera roll, including 20,000 or more photos and videos, while the computer is running for that session. Background auto-backup is optional; a permanently running desktop is not required. Several phones may share one computer.

Two jobs:

1. **Back up the library.** Send the whole library, resume interruptions, then send new or changed originals on later visits. Mirror deletions only when explicitly chosen.
2. **Send a handful now.** Send selected photos or videos from the library or another app's share sheet.

The product must answer “what is safely saved on this computer?” using durable receipts, scoped to the selected computer and backup folder. Queue completion, elapsed quiet time and a count from another computer are not evidence.

## Positioning

Pherry is local photo backup into normal folders. The computer is a receiver; the phone chooses what to send. Files are verified with SHA-256 before a completed receipt is issued. Transfers resume from an acknowledged chunk boundary. Each paired phone has its own folder and its own copy, even when another phone has identical bytes.

The visual metaphor is a photo lab. Product language stays literal: phone, computer, backup, files, saved, already here, paused, waiting, retry and remove.

## Operating context

- **Pairing is required.** The QR contains `ip:port?t=code`; the six-character code enrolls a phone and is exchanged for a per-phone credential. mDNS `_pherry._tcp` helps find the computer. Stable computer and library identities keep receipts separate from changing LAN addresses.
- **Phone identity and folders.** Desktop Settings lets users rename a phone or remove its access. The display name can change without renaming its stable directory. Albums live inside that directory. Removing access preserves saved files.
- **Backup preparation.** The phone checks source versions, computes or reuses SHA-256, checks which files the selected receiver already has, and checks required space before uploading. Hashing overlaps sending through a bounded queue, with three transfer workers by default and six in high-speed mode. Scanning, waiting, sending, verification, pause, failure and completion are distinct states with typed reasons and app-owned wording.
- **Interrupted transfers.** Android persists jobs, queue records, offsets, source versions and receipts in Room. Manual transfers use Android's user-initiated transfer jobs on API 34+; WorkManager handles background execution and retry. User pause survives process restarts. The receiver persists partial-file offsets and jobs in SQLite and automatically restarts after a crash, with bounded retries.
- **Share intake.** Shared media is reviewed before sending and is filed under the sending phone's `Shared` album.
- **Receiver.** The desktop shows a separate card for each unfinished job and receipts for recent finished jobs. Completion comes from the protocol. Pairing instructions collapse after a phone is paired or starts a job and can be reopened.
- **Library and history.** Photos queries the permanent media inventory. History is a separate, capped list of recent transfer events. Both search and filter on the receiver by phone, album, file type, name and saved date. The DOM contains only visible rows and a small overscan window.
- **Maintenance.** Export/import restores records for files present on disk; it does not back up the file bytes or restore pairing credentials. Rebuild scans existing files. Duplicate cleanup stays within a phone's ownership. Clearing History leaves the media inventory, duplicate index and files intact.

## Architecture

- **Android:** Compose, Hilt, Room, WorkManager, Coil and DataStore; minSdk 30 and targetSdk 37. Receipts are scoped by receiver identity, library identity, content URI and source version. A changed original is checked again.
- **Desktop renderer:** native ES modules and CSS. A shared query controller rejects stale responses, retains at most six pages of 120 records, and windows the rendered rows. Thumbnail requests use a 24 MiB renderer LRU, at most three requests at once, and stop queued work while the window is hidden.
- **Desktop main process:** window, tray, dialogs, notifications and a narrow validated preload/IPC bridge. The transfer service runs in an Electron utility process so receiver storage and hashing do not occupy the window/tray event loop.
- **Receiver:** Express, built-in Node SQLite, indexed media and activity tables, persistent phone credentials, jobs and resumable uploads. SQLite uses WAL and durable writes. Port 3210 is the default.
- **Transfer protocol:** 4 MiB maximum chunks, acknowledged offsets, SHA-256 verification and a final file receipt. Partials live below the destination's `.pherry/uploads` directory; the completed original is committed into the phone/album directory after verification.

## Capabilities and constraints

- Maximum file size is 16 GB. Originals are not transcoded or compressed by Pherry.
- Transfers are identified and verified by SHA-256.
- Identical content within one phone may reuse an existing receipt, including when it appears in another album. Different phones keep independent copies.
- The transfer ledger retains the newest 5,000 events. The Photos inventory has no corresponding 5,000-file display cap.
- Video poster availability depends on the operating system and installed codecs. A video/file icon remains a usable fallback.
- A quick backup check verifies receiver presence; the optional integrity check rehashes saved files. Do not describe every quick check as a full disk integrity audit.
- Auto-backup remains subject to Android scheduling, battery, permission and foreground-service constraints. A paused queue must not be silently resumed by automatic work.
- **Local HTTP, without TLS.** Pairing provides access control and per-phone authorization; it does not encrypt network traffic. Use a trusted private network. This is not an Internet-facing storage service. No remote deployment or cloud service is required.

## Data and recovery

Pherry is pre-release and does not migrate data from earlier builds. A change to the Android database schema resets the phone's local transfer state; the next backup checks the computer by content hash, so files already saved there are not sent again. A change to the desktop library schema resets the receiver database: phones pair again, and rebuilding the index restores the Photos inventory from the phone folders. The phone asks for a Pherry Desktop update when the receiver reports an older protocol version.

Moving to another destination folder gives that folder its own library identity. Rebuilding the index scans the paired phones' folders only; other files in the Pherry folder are left alone and not indexed. To include moved files, keep their phone/album directory structure and rebuild the index.

Keep the photo directory and the desktop application-data directory when making an independent backup. A history export alone contains records, not originals. Do not manually remove partial files or the database while the receiver is active.

## Development and verification

From the repository root on Windows, with the project's Android SDK and JDK available:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Desktop development needs Node 24 or newer. From `desktop`:

```powershell
npm ci
npm run check
npm test
npm run benchmark
npm start
```

The optional renderer integration harness is `desktop/scripts/renderer-browser.cjs`. It uses mocked IPC and does not touch a real receiver or user files. Install Playwright in your development environment, or set `PHERRY_PLAYWRIGHT_MODULE` to an existing Playwright module directory. Set `PHERRY_CHROMIUM` if using an existing Chromium executable instead of Playwright's installed browser. No production dependency is required. From the repository root:

```powershell
node desktop/scripts/renderer-browser.cjs
```

Set `PHERRY_RENDERER_ARTIFACTS` to a local output directory to save screenshots during that run.

The real Electron integration harness uses the same Playwright module setup and the Electron executable installed by `npm ci` (or `PHERRY_ELECTRON`). It starts a hidden application with temporary user data, a temporary photo folder and a free port. It pairs a test phone, acknowledges the first 4 MiB of an upload, terminates only that application's receiver child, waits for automatic recovery, then resumes and verifies every saved byte through the actual preload and receiver. It also checks indexed search and the completed job receipt. It removes its temporary files when finished and does not change login settings or advertise a test receiver through discovery. From the repository root:

```powershell
node desktop/scripts/electron-integration.cjs
```

With an Android emulator already running, the real Kotlin/Node protocol tests run against an isolated receiver:

```powershell
node desktop/scripts/android-integration.cjs
```

The runner selects a running emulator, starts the receiver on a free loopback port, and uses an emulator-specific `adb reverse` tunnel. It removes that tunnel and its temporary library afterward and checks that the protocol tests actually ran. If several emulators are running, set `PHERRY_TEST_EMULATOR` to the desired `emulator-…` serial. Physical phones are excluded. CI runs the same command on an API 35 emulator, alongside the Windows/Linux receiver tests and Android build, unit tests and lint. This verifies the protocol through real sockets, not Wi-Fi throughput.

`npm run benchmark` runs an isolated loopback receiver with temporary files: 32 files of 256 KiB and four files of 8 MiB, at concurrency 2, 3, 4 and 6. It reports hashing time, receiver time and loopback throughput, then removes its temporary data. Use it to compare local changes on the same computer. It does not measure phone performance or Wi-Fi throughput, and its numbers are not product speed claims.

Evidence from the renderer integration harness at a 1280×820 viewport: a synthetic 20,000-file collection rendered 42 photo frames; the 5,000-event History rendered 13 rows. Both views stayed below 700 DOM nodes with the current fixture, and thumbnail concurrency stayed at three. The fixture loads real image data and verifies that arrival updates reuse those image nodes, never announce Loading again, preserve focused files, and keep a deep scroll position when the window returns from the background. It also checks Home/End navigation, a jump to item 19,001, global search beyond the first page, phone filters, aliases, a quiet pairing ticket during a job, authoritative job state, and all four views at 480×560.

These are functional and DOM-bound checks, not measured Wi-Fi throughput, battery results, or a completed real-device 20,000-file backup. Do not invent transfer-speed claims. Receiver tests exercise ownership, resumable offsets, checksum rejection, deletion failures and inventory beyond the history cap; Android tests cover transfer policy and presentation.

## Brand commitments

Name: **Pherry**, retained from Photo + Ferry. Package: `com.appharbor.pherry`. Author: AppHarbor. License: MIT. The lab envelope and contact sheet design is defined in `DESIGN.md`; earlier Stitch explorations are historical references.

## Product principles

1. **The truth of the backup is the product.** Saved, already present, in progress and failed have different meanings. A completed receipt belongs to a particular phone, computer, library and source version.
2. **Deletion is explicit.** Mirror deletion and duplicate cleanup name the affected files and keep ownership boundaries. Failed deletions remain failures; removing a history entry never silently deletes the original.
3. **Pairing is the first impression.** Explain the local-network requirement and provide QR, discovered-computer and manual-address routes with actionable errors.
4. **Long jobs stay calm.** Persist progress, resume safely, throttle presentation work and keep browsing responsive while files arrive.
5. **Local and owned.** Originals remain in a normal folder the user controls. No account or cloud upload is required.
