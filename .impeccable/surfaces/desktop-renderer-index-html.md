---
version: 1
slug: "desktop-renderer-index-html"
primary_target: "desktop/renderer/index.html"
related_targets: ["app/src/main/java/com/appharbor/pherry/MainActivity.kt"]
---

## Scope

Whole product redesign: the Android app (all screens, sheets, dialogs, onboarding), Pherry Desktop's renderer (all views and the shell), and the Pherry mark (launcher, desktop window/tray icon, in-app). Visitor mode: **Operate** on both. The desktop is mostly a tray app opened briefly, sometimes left open as a monitor while a phone sends.

Audience and job: strangers installing an open-source tool, backing up a whole camera roll and ferrying a handful of photos now. Key moments, all owed: first pairing, the big first backup, the days-later "am I backed up?" glance, quick-send (library selection and share sheet).

Constraints chosen: transfer engine, dedup, server endpoints and QR payload stay as they are. Desktop stays vanilla ES modules. Fonts and icons ship inside both apps (no runtime Google Fonts calls: local and owned).

## Direction contract

THESIS: Pherry is a photo lab you own: the phone drops film off, the computer develops and keeps it. Every backup is a job written on a yellow envelope; every library, arrival and history is a contact sheet. It refuses the cloud-sync dashboard: stat-card grids, gradient progress bars, cloud glyphs, teal.

OWN-WORLD: Photo-print white paper ground (#F5F5F2) by day, darkroom ground (#161513) by night. Film-black strips (#121110) carry edge-print orange (#F39A2C) frame numbers and per-frame status in Martian Mono. One committed envelope-yellow field (#FFC629) per screen holds the job, with ink-black (#151412) controls printed on it; yellow also marks what is in the job (grease-pencil rings). Safelight red (#D4202C) only for rejects and deletions; a small green lamp only for a live link. Archivo condensed caps for printed form labels, Archivo text everywhere else. Square 4px print corners, perforated tear lines, the envelope's thumb-cut notch. Flat fields, no gradients, glass or shadow-as-depth. Phosphor icons.

STORY: A stranger reads the envelope and knows at once what is on the phone, what is on the computer and what is waiting; pairs by claiming the desktop's ticket; marks frames in grease pencil to send; watches each frame develop as it lands.

FIRST VIEWPORT: Phone Home: top bar with the mark and the computer's lamp; the yellow envelope fills about 60% of the screen with ON PHONE, ON COMPUTER and WAITING as printed form fields, the waiting count at display size, one ink-black "Back up 38 now" button, order-form checkboxes (auto-backup, Wi-Fi only, while charging) and a date stamp for the last backup; below it a film strip of the last frames sent. Desktop Receiver: the claim ticket on the left (QR, pairing code as the ticket number above a perforation, address), the day's arrivals as a contact sheet on the right developing in place, a live job line across it while a phone is sending.

FORM: photo-lab drop-off envelope plus darkroom contact sheet; position 1 of my ordered grounded list, taken as Impeccable's pick over the assigned position 7 (House Tube Post) by the user's choice; seed f97e5fc4. Raises kept from the round: whole-cell thumbnail density with selection drawn on the image (doujin catalog); flat colour owning whole regions (zoo map); guide words naming the span of every long list (lexicon); one fixed size ramp for file weight (star atlas).

SIGNATURE: arriving frames develop from an orange negative into the print; selecting a frame draws a yellow grease-pencil ring around it. Reduced motion falls back to a crossfade.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

## Open decisions

- Download/release URL for Pherry Desktop is unknown; first-run copy must not invent one.
