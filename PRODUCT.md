# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

The phone app is native Android (Jetpack Compose, Material 3). The receiving end, Pherry Desktop, is an Electron app whose renderer is plain HTML, CSS and ES modules; it is judged by desktop-app conventions (Windows first, macOS and Linux welcome), not by Android ones.

## Users

People with an Android phone and a computer on the same Wi-Fi who want their photos and videos on that computer, in original quality, without a cloud account or a cable. Pherry is about to be open-sourced, so the primary user is a stranger who found it on GitHub (later possibly the Play Store), installs both halves with no one to explain them, and has to trust it with their whole camera roll.

Two jobs:

1. **Back up the library.** Get everything on the phone onto the computer once, then keep it current (manually or with auto-backup), sometimes mirroring deletions.
2. **Ferry a handful now.** Send a few specific photos or videos to the computer right away, from the library or from another app's share sheet.

## Product Purpose

Move photos and videos from an Android phone into a plain folder on the user's own computer over the local network, verified and de-duplicated, and make the state of that backup legible on both devices. Success means a first-time user pairs in under a minute, a 20,000-file library transfers without babysitting, and the user can always answer "is everything on my computer?" truthfully at a glance.

## Positioning

Pherry is purpose-built photo backup to a folder you own, not cloud photo storage and not a generic two-way file drop. Its mechanism: the desktop is a dedicated receiver that writes originals into album-named folders, every upload is MD5-verified before it is committed, duplicates are skipped on both sides, and an optional Sync mode mirrors deletions from phone to computer behind an explicit confirmation and a pairing code. No account, no server outside the user's network.

## Operating Context

- **Pairing.** The desktop shows a QR code (payload `ip:port?t=token`), its local address and a six-character pairing code. The phone pairs by scanning the QR (Google code scanner, no camera permission), by picking a desktop found on the network (mDNS `_pherry._tcp`), or by typing the address. Desktops remember identity by a stable device id, so delete rights survive DHCP address changes.
- **Phone, occasional and long-running.** Opened now and then to check status or start a backup; transfers of thousands of files and many GB run in the background through WorkManager with a progress notification. Settings cover Wi-Fi only, charging-only auto-backup (checks every 15 min), up to 6 parallel uploads, keep-screen-awake, confirm-before-desktop-deletes, theme and dynamic color.
- **Share intake.** Media shared from any app opens a review sheet and lands in a `Shared` folder on the computer.
- **Desktop, ambient.** Pherry Desktop usually lives in the system tray with the receiver running, notifying on arrival. Its window is opened to pair, to watch arrivals, browse received media, search the transfer ledger, and maintain it (export/import history, rebuild the dedup index, clean duplicates, rotate the pairing code, change folder or port).

## Capabilities and Constraints

- Terminology in the code and UI: *desktop* / *receiver*, *pairing code* (token), *bucket* (the phone album name, used as the desktop subfolder), *Add* mode (send new, never delete) vs *Sync* mode (send new and remove from desktop what was deleted on the phone), *Shared* folder.
- Limits stated by the code: 16 GB max per file; MD5 mismatch rejects the file; uploads land as hidden `.part` files and are renamed only after verification; the desktop keeps a capped activity ledger.
- Android: Compose + Material 3, Hilt, Room, WorkManager, Coil, DataStore; minSdk 30, targetSdk 37.
- Desktop: Electron 42, Express server on port 3210 by default, Bonjour advertising, tray, native notifications, login item.
- The user lifted every constraint for the redesign. Decision taken: the transfer engine, dedup logic, server endpoints and QR payload stay as they are; the redesign changes experience, identity, structure and copy on top of them.

## Brand Commitments

- Name: **Pherry** (retained; it is also the package id `com.appharbor.pherry` and the `_pherry._tcp` service type). Author: AppHarbor. License: MIT.
- The photo-ferry metaphor is optional; the user left it and the whole visual identity (logo, palette, type, style) open. The previous teal chevron-and-wave mark is not binding.

## Evidence on Hand

- Real behavior verifiable in code: MD5 verification, two-sided dedup, Sync deletion gated by pairing code, mDNS discovery, background auto-backup, share-sheet intake, history export/import, dedup index rebuild.
- No users, testimonials, benchmarks, transfer-speed figures or press exist. Do not invent them.
- Earlier explorations (Google Stitch mockups, "Azure Stream") live in `docs/stitch_photosender_android_prd/`; they are history, not authority.

## Product Principles

1. **The truth of the backup is the product.** Every screen answers what is on the phone, what is on the computer, and what is in between. Never say "all caught up" when it isn't.
2. **Deletion is never a side effect.** Anything that removes files or history (Sync deletes, clear history, duplicate cleanup) is named, counted, previewed and confirmed.
3. **Pairing is the first impression.** A stranger with no docs gets from install to first file in under a minute, and every failure tells them what to do next.
4. **Long jobs stay calm.** Big transfers run in the background, legible at a glance, with no babysitting and no anxiety-inducing noise.
5. **Local and owned.** Files arrive as originals in a normal folder the user controls; no accounts, no cloud, nothing to sign up for.
