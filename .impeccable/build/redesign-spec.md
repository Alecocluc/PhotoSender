# Pherry redesign: build spec for screen agents

Read this whole file before writing code. It is the contract for every screen. The direction contract lives in
`.impeccable/surfaces/desktop-renderer-index-html.md`; product truth lives in `PRODUCT.md`. Old behaviour of any
screen you rebuild is in git: `git show HEAD:<path>` (read it; every capability it had must survive unless this
spec says otherwise).

## The world in one paragraph

Pherry is a photo lab you own. The phone drops film off; the computer develops and keeps it. **Envelope yellow**
(`PherryTheme.colors.envelope`, #FFC629) is one committed field per screen that holds *the job* (what is waiting /
sending / selected); everything printed on it uses `onEnvelope` / `onEnvelope2`. **Film black** strips
(`colors.film`) carry **edge print** (`colors.edge`, orange, Martian Mono condensed caps) with frame numbers and
per-frame status. Grounds are **photo-print paper** (light, #F5F5F2) or **darkroom** (dark, #161513); raised print
surfaces are `colors.sheet`. **Safelight red** (`colors.red`) only for failures and deletions. A small **green lamp**
only for a live link. Square print corners (4dp; frames 2dp). Flat colour fields; no gradients, glass, glow, drop
shadows as depth, gradient text, or coloured side-stripes on cards. Phosphor icons only.

## Copy rules (both platforms)

- Plain, specific, second person. Name the computer (`serverName`, fallback "your computer"). Sentence case for
  titles and buttons ("Back up 38 now", "Pair a computer"). Edge print and form labels are caps via style, not via
  typing caps in strings (Android: `.uppercase()` at the call site is fine for edge/form labels only).
- Buttons name the action and, when useful, the count: "Send 12", "Sync and delete 4", "Retry 3".
- Errors say what happened and what to do next. No "Oops", no exclamation marks, no emoji, no lab puns in words
  (the metaphor is visual; words stay literal: photos, videos, computer, send, back up, sync, delete).
- Never invent facts: no speeds, user counts, URLs or features the code doesn't have. The Pherry Desktop download
  URL is unknown: say "Install Pherry Desktop on your computer" without a link.
- Pluralise properly (`Fmt.plural`, `plural()` on desktop). Group digits (`Fmt.count`, `n()`).
- Mono is for data only: sizes, counts, addresses, codes, dates in edge print. Running text is Archivo.
- No eyebrow/kicker labels above headings. Data lines go *below* titles (ScreenHeader `data =`).

---

## ANDROID

Package root: `app/src/main/java/com/appharbor/pherry`. Compose + Material 3. Exemplar to imitate:
`ui/home/HomeScreen.kt` (read it first, fully). Shell: `MainActivity.kt` (top app bar with `PherryWordmark` +
`ComputerChip`, M3 `NavigationBar` with yellow indicator, `LocalSnackbarHost` for snackbars, connect sheet and
share sheet as `ModalBottomSheet`). Do **not** edit `MainActivity.kt`, `ui/theme/*`, `ui/components/*`,
`ui/home/*` unless your task says so. If you need a small extra composable, write it `private` in your own file.

### Theme API

- `PherryTheme.colors`: `paper, sheet, well, well2, rule, ruleStrong, ink, ink2, ink3, film, film2, filmRule,
  filmInk, edge, envelope, envelopePressed, onEnvelope, onEnvelope2, red, redWash, onRedWash, green, isDark`.
- `PherryTheme.text`: `formLabel` (condensed caps label), `edge` (edge print), `mono`, `monoCaps` (data line,
  call `.uppercase()`), `code` (pairing code), `wordmark`.
- `MaterialTheme.typography`: displayLarge 64 (envelope count), displayMedium 48, headlineLarge 32 (screen
  titles via ScreenHeader), headlineMedium 26, headlineSmall 22, titleLarge 20, titleMedium 16, titleSmall 14,
  bodyLarge/Medium/Small, labelLarge (buttons) / Medium / Small. Never hand-pick font sizes; use roles.
- `Spacing`: xxs 2, xs 4, sm 8, md 12, lg 16, xl 24, xxl 32, xxxl 48, `screen` 16 (page gutter), `touch` 48.
- `PherryShape.frame` (2dp), `.print` (4dp), `.button` (4dp), `.sheetTop`.
- M3 colour roles are mapped: `primary` = ink, `primaryContainer` = envelope yellow, `surface` = paper,
  `surfaceContainerLowest` = sheet, `error` = red. Prefer `PherryTheme.colors` in new code.

### Component kit (`ui/components`)

- `PherryMark(size)`, `PherryWordmark(markSize)`.
- `PhIcon(Ph.X, contentDescription, tint, size = 24.dp)`; `Ph` lists every icon (House, Images, Transfers, Gear,
  Desktop, Phone, QrCode, Scan, Wifi, WifiSlash, Refresh, Upload, Send, Check, CheckBold, CheckCircle(Fill), X,
  XBold, Warning, WarningCircle(Fill), Info, Trash, Search, CaretRight(Bold), CaretDown, ArrowLeft, ArrowRight,
  Play, Pause, Stop, Image, Video, File, FilmStrip, Key, Moon, Sun, Monitor, Link, Plugs, Plug, Lightning,
  BatteryCharging, Clock, History, SelectAll, Camera, Share, Eye, SealCheck, ShieldCheck, Lock, Hourglass,
  CloudSlash, Calendar, Sparkle, Dots, Copy, Undo, Question, Ticket, Stamp, Folder). **Never use
  `androidx.compose.material.icons`** (that dependency is being removed).
- `Envelope(notch = true) { … }`: the yellow job field with the thumb-cut notch. Content colour is onEnvelope.
  `EnvelopeField(label, value)`, `EnvelopeCheck(label, checked, onCheckedChange, enabled)`, `DateStamp(text)`,
  `Perforation(color)`, `JobBar(progress, onEnvelope = true)`.
- Film: `Frame(model, contentDescription, aspectRatio, isVideo, selectedNumber: Int?, develop: Float 0..1,
  failed)` (Coil image; yellow grease-pencil ring + number when `selectedNumber != null`; `develop` 0 = orange
  negative → 1 = print); `FilmRow(count, columns, sprockets, edgeTop = { i -> … }, edgeBottom = { i -> … }) { i ->
  frame }` (one black strip, N equal frames, edge print rows); `ScrollingStrip(count, frameWidth, edgeTop) { i -> }`;
  `EdgeText(text, dim)`; `FrameNumber(number, trailing)`; `Sprockets()`; `developFilter(progress)`.
- Print: `PrintButton(text, onClick, style = Ink|Outline|Quiet|Danger|DangerOutline, icon, enabled, onEnvelope)`
  (≥48dp); `Lamp(LampState.On|Busy|Off|Idle, onEnvelope)`; `StatusTag(text, TagKind.Saved|Job|Waiting|Reject|
  Quiet)`; `Notice(title, detail, actionLabel, onAction, error = true)`; `EmptyStrip(title, body, action)`;
  `ScreenHeader(title, data, trailing)`; `SectionHeading(text, trailing)`; `GuideWords(text, trailing)` (sticky
  span header); `SwitchRow(title, checked, onCheckedChange, subtitle, icon, enabled)`; `ActionRow(title, onClick,
  subtitle, icon, value, danger)`; `PrintSegmented(options: List<Pair<T,String>>, selected, onSelect)`; `Hairline()`.
- `SelectionTicket(count, detail, actionLabel?, onAction, onClear, hint?, onHint?, applyNavigationPadding)`.
- `SyncConfirmDialog(plan, computerName, confirmDestructive, onConfirm, onDismiss)`.
- `Fmt.count/bytes/speed/remaining/plural/stamp/ago/day/clock/isVideoName`.
- `LocalSnackbarHost.current.showSnackbar(...)` for transient results (in a coroutine).

### Platform rules

Material 3 components themed, never ported iOS controls. 48dp touch targets. Edge-to-edge: the Scaffold in
MainActivity already pads top bar / nav bar; full-screen screens (album, viewer, onboarding) must handle
`WindowInsets` themselves (`statusBarsPadding`, `navigationBarsPadding`, `imePadding`). System Back must work
(`BackHandler` for in-screen overlays like a viewer or selection mode). Every image/icon that carries meaning has a
`contentDescription`; decorative ones `null`. Lists: `LazyColumn`/`LazyVerticalGrid` with stable keys. Dark theme
must work (all colours from the theme). Snackbars, not Toasts.

### A1 · Library (`ui/gallery/GalleryScreen.kt`, `ui/gallery/FolderDetailScreen.kt`, `ui/gallery/GalleryViewModel.kt`)

Signatures are fixed (see the current stubs). `FolderDetailScreen` gained `onConnectClick` and `connectionState`.

ViewModel additions (UI-level reads only; never change upload engine behaviour): load `completedIds` from
`uploadManager.completedMediaStoreIds()` when folders load; expose per-album summaries: name, item count, count not
yet backed up, up to 6 newest item URIs (+ isVideo) for the album's preview strip (derive from
`mediaRepository.loadAllMedia(filter)` grouped by `bucketName`, newest first by `dateModified`); expose the total
unsent count and bytes of the current selection (`selectedBytes`). Keep `selectAllMedia`, `selectRecent(30)`,
`selectNewSinceBackup`, `startTransfer`, `toggleSelection`, `selectAll(items)`, `deselectAll`, filters.
The Library no longer hosts the Add/Sync mode toggle or the Sync button: Sync lives on Home and Settings. Remove
the mode UI here but leave `uploadMode`/`prepareSync`/`confirmSync` in the ViewModel (unused is fine).
Browsing must work **without a computer connection** (only media permission is needed); loading folders must not
wait for a connection. Sending needs a connection: the SelectionTicket shows "Pair a computer" (hint) instead of
the send action when disconnected.

GalleryScreen (Library tab):
- `ScreenHeader("Library", data = "4,212 items · 23 albums · 38 not backed up")`.
- No permission: an `EmptyStrip`-style block with "Allow photo access" + PrintButton (same permission handling as
  Home, including the permanently-denied case → open app settings).
- Controls row: `PrintSegmented(All / Photos / Videos)` + a search `IconButton` (48dp) that expands an
  `OutlinedTextField` themed with ink focus colours (square 4dp shape) to filter albums by name.
- Quick picks (only when connected or not, always visible when there's media): a row of M3 `FilterChip`s or
  outlined `PrintButton`s: "New since last backup (38)", "Last 30 days", "Everything (4,212)". They add to the
  selection.
- Albums: each album is one film strip (`FilmRow` with sprockets, 4 columns on phones, 6 on width ≥ 600dp) showing
  its newest frames; edge print above the strip: album name (EdgeText, not dim) + count; a `StatusTag("38 new",
  TagKind.Job)` when it has unsent items, or a tick "ALL BACKED UP" dim edge text when none. The whole strip is one
  48dp+ clickable → `onFolderClick(name)`. Albums with fewer frames leave empty slots blank film.
- Empty library (permission granted, no media): `EmptyStrip("No photos or videos yet", …)`.
- Search with no match: `EmptyStrip("No album called “x”", …)`.
- Selection: `SelectionTicket` slides up from the bottom (respect the nav bar: this screen sits above the app's
  NavigationBar, so `applyNavigationPadding = false`). Detail = "48 MB · to ALEX-PC". Action "Send 12" →
  `onBeforeTransfer(); viewModel.startTransfer(); onTransferClick()`. Disconnected → hint "Pair a computer" →
  `onConnectClick`.
- Sync result snackbars (if any) via LocalSnackbarHost.

FolderDetailScreen (Album, full screen, no app bars from the shell):
- Own top bar: M3 `TopAppBar` with back arrow (Ph.ArrowLeft, "Back"), title = album name (titleLarge, ellipsized),
  data line under it in monoCaps ("212 ITEMS · 18 NOT BACKED UP" or "12 SELECTED"), actions: "Select new"
  (selects unsent in this album), "Select all"/"Clear" (SelectAll icon toggles). Handle status bar insets.
- The contact sheet: rows of film (`FilmRow` without sprockets, 3 columns on phones, 4 at ≥ 600dp, 6 at ≥ 840dp)
  inside a `LazyColumn` (chunk items into rows; key by first item id). Edge print above each frame: frame number in
  this album (1-based, `FrameNumber`). Edge print below each frame: a tick `EdgeText("✓ SAVED")`-style mark when the
  item is already backed up (use `PhIcon(Ph.CheckBold, tint = colors.edge, size 10.dp)` + `EdgeText("ON PC", dim
  = true)`), nothing otherwise. Rows separated by 10dp of paper.
- Tap: when selecting → toggle; otherwise open the viewer. Long-press → start selecting with that frame.
  Selected frames show the grease ring with their selection order number (`selectedNumber`). Back while
  selecting clears the selection (`BackHandler`).
- Viewer: full-screen black overlay with `HorizontalPager` over the album, photo `contentScale = Fit` (video shows
  its frame with a play badge; no playback needed), top bar (close X, file name), bottom info strip in film black:
  edge-print line with size, date, "ON PC" / "NOT BACKED UP", and a `PrintButton("Send this", icon = Send)` that
  selects it and starts the transfer (or "Pair a computer" when disconnected). `BackHandler` closes it. Insets.
- `SelectionTicket` at the bottom with `applyNavigationPadding = true` (no app nav bar here).
- Loading state: film rows of empty `film2` frames (no spinners).

### A2 · Transfers (`ui/activity/ActivityScreen.kt`; may edit `ui/transfer/TransferViewModel.kt`, `ui/history/HistoryViewModel.kt`)

Signature: `ActivityScreen(onOpenLibrary: () -> Unit)` with ViewModels obtained via `hiltViewModel()` inside.
Keep the keep-screen-awake behaviour (SettingsViewModel.keepScreenAwake + `view.keepScreenOn` while transferring).

- `ScreenHeader("Transfers", data = …)` then M3 `SecondaryTabRow` themed (ink indicator 2dp, ink/ink2 text, paper
  container) with tabs "Now" and "History" (rememberSaveable selection).
- **Now**, transferring or with a finished batch in memory (`totalFiles > 0`):
  - An `Envelope` job panel (like Home's SendingBody but larger): lamp busy, label "SENDING TO <COMPUTER>" or
    "FINISHED" when done; display count `412 / 1,284`; `JobBar`; monoCaps line: bytes, speed, time left;
    secondary line: "12 failed · 8 already on computer" where non-zero; buttons: "Stop" (Outline, onEnvelope) when
    transferring (call `cancelTransfer()`, snackbar "Stopped. The rest stay queued until you resume.").
  - "On the line now": the files currently uploading (status UPLOADING, usually 1–6) as a `FilmRow` (3–6 columns)
    whose frames **develop with their real upload progress** (`develop = bytesTransferred / fileSize`); edge print
    above: percentage; below: size. Non-previewable files (no image URI) show a film2 frame with the file/video
    icon. This is the signature moment: get it right.
  - Summary lines (ledger rows, hairline separated): "1,092 waiting", "386 sent", each with a mono count.
  - Failed files listed individually (thumbnail Frame with `failed = true`, name, size), and a `Notice` with
    "Retry N" (HistoryViewModel.retryFailed).
  - "Already on the computer" (skipped duplicates): first 3 rows "IMG_1234.jpg — same as IMG_0042.jpg in Camera"
    then "and N more"; explain in one line: "These were identical to files the computer already has, so nothing
    was sent."
  - Nothing at all: `EmptyStrip("Nothing is sending", "Back up from Home, or pick photos in the Library.")` with
    a `PrintButton("Open Library", onClick = onOpenLibrary, style = Outline)`.
- **History**:
  - A sheet-coloured summary block (not cards, not stat tiles): one data line "4,174 SENT · 18.2 GB · LAST OCT 1
    23:14" in monoCaps, then the integrity check: title "Check the backup", body "Ask <computer> whether every
    file sent from this phone is still there. Missing files are queued again.", `PrintButton("Check now",
    Outline)` (disabled while verifying, shows "Checking 120 of 4,174…" progress via a thin JobBar onEnvelope =
    false), and the result summary line when done. Failed count → `Notice` with Retry.
  - Then the ledger: `LazyColumn` with sticky headers per day (`stickyHeader { GuideWords(Fmt.day(ts), trailing
    = "N FILES") }`); rows: 56dp thumbnail `Frame` (contentUri, 4:3? use 1:1 at 48dp), file name (titleSmall,
    ellipsized middle is fine), monoCaps line "2.4 MB · CAMERA", time on the right (`Fmt.clock`). Raise the history
    window to the last 500 records (edit `HistoryViewModel.recentHistory` limit) and show "Showing the last 500"
    at the end when hit.
  - "Clear history" as a `PrintButton(style = DangerOutline)` at the end of the list with an M3 `AlertDialog`:
    title "Clear transfer history?", text "This phone forgets which files it sent. Files on <computer> stay where
    they are. The next backup checks with the computer, so nothing is sent twice." Confirm "Clear history" (red
    text button). Note: verify that last sentence is true by reading `UploadManager` (dedup via the server's
    `/exists` md5 check); if it isn't, say instead "The next backup may send some files again."
  - Empty: `EmptyStrip("No transfers yet", "Files you send appear here, newest first.")`.

### A3 · Settings (`ui/settings/SettingsScreen.kt`, may edit `ui/settings/SettingsViewModel.kt`)

Signature: `SettingsScreen(onManageComputer: () -> Unit)`. Expose from SettingsViewModel (inject
`ConnectionManager`): `connectionState`, `serverName`, `connectedEndpoint`, and `disconnect()`.

Layout: `ScreenHeader("Settings")`, then sections with `SectionHeading` and rows (no cards, no boxes; hairlines
between rows via `Hairline()` where it helps scanning):
- **Computer**: a sheet-coloured block (`colors.sheet`, 4dp corners, 1dp rule border) showing `Lamp` + computer
  name (titleMedium) + endpoint in mono (or "Not paired"), and buttons "Change computer" (Outline →
  onManageComputer) and "Disconnect" (Quiet, red text; confirm with AlertDialog "Disconnect from X? Pherry
  reconnects to X the next time it opens or auto-backup runs. To stop backups, turn off auto-backup."; Disconnect
  only drops the live link, the pairing stays).
- **Backup**: "When you back up" `PrintSegmented(Add new only / Mirror this phone)` with explanation below
  (bodySmall): Add = "Sends new photos and videos. Never deletes anything on the computer."; Mirror (Sync) =
  "Sends new ones and deletes from the computer what you delete here. You confirm each time." Then SwitchRows:
  Auto-backup (with the same "Everything / Only new" dialog as before), "Only while charging" (enabled only when
  auto-backup is on), "Wi-Fi only", "Ask before deleting from the computer" (confirmDestructiveSync).
- **Transfers**: "Faster transfers" (highSpeedTransferEnabled; "Sends up to 6 files at once. Turn off if your
  Wi-Fi drops."), "Keep screen on while sending".
- **Appearance**: Theme `PrintSegmented(System / Light / Dark)`; SwitchRow "Use wallpaper colours" (dynamic
  colour; subtitle "Replaces Pherry yellow with colours from your wallpaper. Android 12 and newer."; disabled
  below API 31).
- **About**: `PherryMark(48.dp)` + "Pherry" + "Version x.y" (read `BuildConfig`? not enabled; use
  `context.packageManager.getPackageInfo(context.packageName, 0).versionName`), a line "Open source, MIT licence."
  and "Photos travel only between this phone and your computer, over your own network." No links (URL unknown).

### A4 · Onboarding + Connect (`ui/onboarding/OnboardingFlow.kt`, `ui/connect/ConnectSheet.kt`, may add `ui/connect/PairingParts.kt`, may edit `ui/connect/ConnectViewModel.kt`)

`OnboardingFlow(onFinish: () -> Unit)` replaces the old OnboardingScreen + PairingScreen. Full screen; handles
system bar and IME insets itself; System Back goes to the previous step (BackHandler), and on the first step
leaves the app normally. Steps, with a quiet step indicator made of three tiny frames in edge print ("1▸ 2▸ 3▸",
the current one in edge orange on a short film strip at the top):
1. **Welcome**: large `PherryMark(96.dp)`, `PherryWordmark`-sized title "Back up your phone to your own
   computer", body "Pherry sends your photos and videos straight to Pherry Desktop over your Wi-Fi. No account, no
   cloud." Three plain facts with Phosphor icons (no cards): "Straight to your computer" / "Files never leave your
   network."; "Checked on arrival" / "Every file is verified before it's saved."; "Only what's new" / "Anything
   already on your computer is skipped." A decorative `ScrollingStrip` or `FilmRow` of 4 empty frames is fine.
   Primary "Get started". 
2. **Pair**: title "Pair your computer", body "On your computer, open Pherry Desktop. It shows a pairing ticket
   with a QR code." A small line "Don't have it yet? Install Pherry Desktop on your computer first." Primary
   "Scan the ticket" (ML Kit `GmsBarcodeScanning`, exactly as the old PairingScreen/ConnectSheet did, with
   `viewModel.onScannedPayload` / `onQrScanError`). Below: **Found on your Wi-Fi** list (NSD
   `nearbyDesktops`, start/stop discovery while this step is visible) as rows: Desktop icon, name, endpoint mono,
   "Pair" text button; when none: a quiet line with a small busy lamp "Looking for computers on this Wi-Fi…".
   "Type the address instead" expands an OutlinedTextField (KeyboardType.Uri, ImeAction.Done, placeholder
   "192.168.1.42:3210", errors from `ipError`/`connectionError` in red supporting text) + "Connect". Recent
   targets as chips if any. When connected: an `Envelope` confirmation "Paired with ALEX-PC" with a SealCheck,
   and "Continue" primary. "Skip for now" quiet button in the corner (goes to step 3).
3. **Photos**: title "Let Pherry see your photos", body "Pherry needs access to back up your photos and videos.
   They only go to your computer." Primary "Allow access" (requiredMediaPermissions launcher); on grant → onFinish.
   "Not now" quiet → onFinish. If already granted, skip this step automatically. On Android 13+ also request
   POST_NOTIFICATIONS? No: notifications are requested before the first transfer elsewhere; don't add it here.
- ConnectSheet (`ConnectSheet(onDismiss, viewModel)`), shown as a bottom sheet from the shell: title "Your
  computer" (headlineSmall). Connected: a `colors.sheet` block with Lamp On + name + endpoint mono, buttons
  "Disconnect" (Quiet red) and "Done"; below it "Scan the ticket again" (Outline, picks up a rotated pairing
  code) and a collapsed "Pair a different computer" disclosure holding the pairing options (Settings' "Change
  computer" opens the sheet with it expanded). Connecting: busy lamp "Connecting to …". Disconnected: primary "Scan the
  ticket", nearby list, recent chips, manual address field + "Connect", errors shown in red. One help line at the
  end: "Phone and computer need to be on the same Wi-Fi." Shared pieces between onboarding step 2 and the sheet go
  in `PairingParts.kt` (QR launcher helper, NearbyList, ManualAddress).

### A5 · Share sheet + platform polish (`ui/share/ShareImportSheet.kt`, `data/upload/UploadWorker.kt` notification bits only, `res/values*/themes.xml`, `res/values/colors.xml`, `AndroidManifest.xml`)

- ShareImportSheet (signature fixed): title "Send to <computer>" (or "Send to your computer"), a `ScrollingStrip`
  of the shared items (Coil can load the shared `content://` URIs; `preview.items[i].uri`, video badge), counts
  line in monoCaps "3 PHOTOS · 1 VIDEO · 24 MB", a `Notice(error = false)` "Pair a computer to send these" with
  action "Pair" when disconnected (calls onConnect), primary `PrintButton("Send 4")` (disabled while
  loading/sending; "Preparing…" while sending), Quiet "Cancel". Footnote bodySmall: "They land in the Shared folder
  on your computer. Anything already there is skipped." Get `serverName` from a ConnectionManager flow: add
  `serverName` to `ShareImportViewModel` (inject is already ConnectionManager).
- Notifications (UploadWorker): use `R.drawable.ic_stat_pherry` as the small icon for both the progress and the
  summary notifications; title "Sending to your computer" during a transfer; keep everything else.
- Theme/splash: `res/values/themes.xml` `Theme.Pherry` parent `android:Theme.Material.Light.NoActionBar` with
  `android:windowBackground` #F5F5F2, `android:statusBarColor`/`navigationBarColor` transparent; add
  `res/values-night/themes.xml` with #161513; add `res/values-v31/themes.xml` + `res/values-night-v31/themes.xml`
  setting `android:windowSplashScreenBackground` (#F5F5F2 / #161513), `android:windowSplashScreenAnimatedIcon`
  `@drawable/ic_launcher_foreground`, `android:windowSplashScreenIconBackgroundColor` #FFC629. Clean
  `colors.xml` to just the brand colours you reference.
- Manifest: add `android:enableOnBackInvokedCallback="true"` on `<application>` (predictive back). Don't change
  anything else in the manifest.

---

## DESKTOP (`desktop/renderer`)

Vanilla ES modules, no build step. Exemplar to imitate: `views/receiver.js` + `views/receiver.css`, plus the shared
`styles.css` (tokens + components: `.btn` (`.primary .danger .quiet .sm .icon-only .on-yellow`), `.input`,
`.select`, `.search`, `.switch` (+ `.track`), `.seg` (buttons with `aria-pressed`), `.notice` (`.info`),
`.print`, `.section-title`, `.label`, `.tag` (`.saved .job .reject .quiet`), `.lamp` (`.on .busy .off`), `.perf`,
`.sheet` + `.frame` (contact sheet), `.empty`, `.guide` (sticky guide words), `.view-head` / `.view-title` /
`.view-sub` / `.view-actions`, `.mono`, `.visually-hidden`). Modules: `icons.js` (`icon(name, {weight, size, label,
cls})`, Phosphor names like "folder-open", "magnifying-glass", "export", "download-simple", "upload-simple",
"trash", "arrows-clockwise", "broom", "key", "copy", "check", "caret-down", "sort-ascending", "funnel", "image",
"video-camera", "file", "monitor", "sun", "moon", "power", "hard-drives", "seal-check", "warning-circle",
"arrow-square-out", "plugs-connected", "clock", "tray"...), `components.js` (`frameHtml(entry, {number, edgeRight,
edgeBottom})`, `wireFrames(root)`, `emptyHtml({title, body, action})`), `shell.js` (`showToast(msg, "error"?)`,
`showConfirm({title, message (trusted HTML: escape any data you put in it), confirmText, danger})`, `applyTheme`,
`renderStation`), `state.js` (`state`, `loadMoreHistory`, `refreshSettings`, `computerName()`), `utils.js`
(`escHtml, entryName, entryTime, entryKey, entryKind, isVideo, isPhoto, fileIcon, fmtBytes, fmtSpeed, fmtClock,
fmtFullTime, fmtStampDate, fmtUptime, n, plural, dayLabel, sameDay, primaryIP, sortedIPs`), `thumbs.js`
(`attachThumbs(root)`, `canThumbnail(name)`), `router.js` (`register(name, renderFn)`, `navigate`, `rerender`).
Don't edit `styles.css`, `index.html`, `shell.js`, `components.js`, `router.js`, `state.js`, `utils.js`, `app.js`,
`views/receiver.*`. Put view CSS in your own `views/<view>.css` (already linked). Escape every piece of file or
device data with `escHtml`. Keyboard: everything reachable and visible on focus; no div-buttons. Views re-render
on arrivals (debounced); preserve focus/caret for text inputs across re-renders (see old history.js trick). Do not
use the browser/Playwright tools (another process owns them); verify by reading your code carefully.

### D1 · Photos (`views/photos.js`, `views/photos.css`)

Register `"photos"`. The received media as contact sheets grouped by day. `view-head`: title "Photos", sub line
"1,284 PHOTOS AND VIDEOS · 4.2 GB" (from status totals; note loaded vs total honestly: "Showing 200 of 1,284"),
actions: `.seg` filter All / Photos / Videos, an album `.select` ("All albums" + each bucket name seen in loaded
items), "Open folder" button. Body: for each day group, a sticky `.guide` header ("<strong>Today</strong>" left,
"14 FRAMES · 38 MB" right), then a `.sheet` of `frameHtml(entry, { number, edgeRight: fmtClock(time), edgeBottom:
bucketName })`, numbers counting down from the total like the Receiver. `wireFrames` + `attachThumbs` after
render. "Load more" `.btn` at the end when more history exists (`loadMoreHistory()`), with a loading state and
error toast. Empty: `emptyHtml({title:"No photos yet", body:"Photos and videos you receive from your phone appear
here, grouped by day."})`; filtered-empty: "No videos in this view" etc. with a "Show everything" `.link`.

### D2 · History (`views/history.js`, `views/history.css`)

Register `"history"`. The ledger. `view-head`: title "History", sub "1,284 TRANSFERS · 4.2 GB · SINCE <first
date>", actions: `.search` (filter by file, album, device), `.select` type (All types / Photos / Videos / Other),
`.select` sort (Newest / Oldest / Largest / Name), and a quiet overflow group: "Export" (download-simple), "Import"
(upload-simple), "Clear" (trash, `.btn.danger`), wired to `exportHistory`, `importHistory`,
`clearHistoryConfirm` from `actions.js`. A `.guide` sticky header that shows the date span of the rows in view
(guide words: "OCT 2 — SEP 28"; compute from visible rows with an IntersectionObserver or on scroll, cheap) and
"N MATCHING" on the right with a "Clear filters" `.link` when filtered. Table: semantic `<table>` with columns No.
(mono, ledger number), File (32px frame thumb using `data-thumb-*` inside a `.frame`-like mini shot, name,
album under it), From (device name or "Unknown"), Type (tag), Size (mono, right-aligned), Saved (mono date+time),
row actions (Open, Show in folder) visible on hover/focus-within as `.btn.sm.quiet.icon-only` with aria-labels.
Double-click/Enter opens. Under 820px: cards instead of the table (same data; raised from 720px so the File column stays readable). Sticky table header below the guide.
Load more as in Photos. Empty and no-match states with `emptyHtml`.

### D3 · Settings + main process (`views/settings.js`, `views/settings.css`, `actions.js`, `../main.js`, `../preload.js`)

Register `"settings"`. Sections as printed form blocks (`.print` with a `.section-title`, rows separated by
hairlines; label column + control column; on narrow widths stack). No icon-tile cards.
- **Receiving**: Pherry folder (path in `.input.mono` read-only + "Choose…" btn + "Open" btn), Port (`.input`
  type number 1024–65535 + "Apply"; inline error text under the field instead of only a toast; warn that changing
  it restarts the receiver and phones must re-pair to the new address), Addresses on this computer (list of
  `ip:port` with copy buttons).
- **Pairing code**: the code large in mono on a small yellow stub (reuse the ticket stub look: yellow card with
  `.perf` top edge), explanation "Phones that scanned this code can delete files here when they use Sync. Get a
  new code if you shared it with someone you don't trust; paired phones then need to scan again." and "Get a new
  code" (`.btn.danger`, confirm first with showConfirm).
- **Behaviour**: switches (`.switch`) for "Show a notification when files arrive", "Open the folder after each
  file", "Keep receiving when the window is closed" (minimizeToTray), "Start Pherry when you sign in"
  (launchAtStartup).
- **Maintenance**: "Back up the history" (Export / Import buttons), "Rebuild the duplicate index" (with live
  progress text "Hashing 1,204 of 4,000…" via the existing `updateRebuildButton`/`startRebuildPolling` in
  actions.js; update that code to the new button markup and ids you use) and "Delete duplicate copies"
  (`.btn.danger`, existing `cleanDuplicates`).
- **Appearance**: `.seg` System / Light / Dark (`aria-pressed`).
- **About**: tile `pherry-icon.svg` 40px + "Pherry <version>" (from `state.host.version` if present) + "Open
  source, MIT licence. Photos travel only between your phone and this computer."
- Server error banner (`.notice`) at the top when `state.server.error`.
- actions.js: keep exports; make every `showConfirm` message safe HTML (escape dynamic values), copy per the rules
  ("Clear the history on this computer?" / "Files stay on disk. This computer forgets which files it already has,
  so phones may send some again." etc.); `updateRebuildButton` must target the new Settings markup.
- main.js (the running app is NOT restarted; these take effect on next launch): add `ipcMain.handle("get-host-info",
  () => ({ hostname: os.hostname(), platform: process.platform, version: app.getVersion() }))`; window `title:
  "Pherry"`; `backgroundColor` #F5F5F2 light / #161513 dark (resolve "system" with `nativeTheme.shouldUseDarkColors`);
  tray tooltip "Pherry · receiving"; tray menu labels: "Open Pherry", "Address: …", "Pairing code: …", "Open the
  Pherry folder", "Quit Pherry"; arrival notification title "Photo received" → "<n> arrived from <device>" style:
  `title: entry.deviceName ? \`From ${entry.deviceName}\` : "New file received"`, `body: fileName`.
- preload.js: expose `getHostInfo: () => ipcRenderer.invoke("get-host-info")`.

### Not for agents

PNG icons (window/tray), `index.html`, `styles.css`, shell, Receiver, Home, theme, components: done by the lead.
