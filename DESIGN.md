---
name: Pherry
description: The photo lab you own. The phone drops film off, the computer develops and keeps it.
colors:
  envelope-yellow: "#FFC629"
  envelope-yellow-pressed: "#F2B600"
  on-envelope: "#151412"
  on-envelope-2: "#4F4316"
  print-ink: "#151412"
  print-ink-2: "#4A4843"
  print-ink-3: "#66635D"
  print-ink-hover: "#2E2C29"
  photo-paper: "#F5F5F2"
  print-sheet: "#FFFFFF"
  rail: "#EEEEEA"
  well: "#EAEAE5"
  well-2: "#E1E1DB"
  rule: "#DADAD3"
  rule-strong: "#BDBCB4"
  film-black: "#121110"
  film-2: "#1D1B19"
  film-rule: "#2C2A27"
  film-ink: "#CFCAC0"
  edge-print-orange: "#F39A2C"
  safelight-red: "#C81E2A"
  safelight-wash: "#FBE5E3"
  on-safelight-wash: "#6A0B12"
  lamp-green: "#1F8A4C"
  darkroom: "#161513"
  darkroom-sheet: "#1F1E1B"
  darkroom-rail: "#11100F"
  darkroom-well: "#262522"
  darkroom-well-2: "#2E2D29"
  darkroom-rule: "#2E2C29"
  darkroom-rule-strong: "#46433E"
  darkroom-ink: "#EDEBE6"
  darkroom-ink-2: "#BDB9B0"
  darkroom-ink-3: "#908C84"
  darkroom-film: "#0B0A09"
  darkroom-red: "#FF6B66"
  darkroom-red-wash: "#3A1513"
  darkroom-on-red-wash: "#FFD9D6"
  darkroom-green: "#4CC27A"
typography:
  display:
    fontFamily: "Archivo, Segoe UI, system-ui, sans-serif"
    fontSize: "64px"
    fontWeight: 800
    lineHeight: 1
    letterSpacing: "-0.02em"
    fontVariation: "'wdth' 88"
  headline:
    fontFamily: "Archivo, Segoe UI, system-ui, sans-serif"
    fontSize: "32px"
    fontWeight: 800
    lineHeight: 1.125
    letterSpacing: "-0.015em"
    fontVariation: "'wdth' 88"
  view-title:
    fontFamily: "Archivo, Segoe UI, system-ui, sans-serif"
    fontSize: "30px"
    fontWeight: 800
    lineHeight: 1.05
    letterSpacing: "-0.015em"
    fontVariation: "'wdth' 90"
  title:
    fontFamily: "Archivo, Segoe UI, system-ui, sans-serif"
    fontSize: "20px"
    fontWeight: 700
    lineHeight: 1.3
    fontVariation: "'wdth' 90"
  title-small:
    fontFamily: "Archivo, Segoe UI, system-ui, sans-serif"
    fontSize: "14px"
    fontWeight: 600
    lineHeight: 1.43
  body:
    fontFamily: "Archivo, Segoe UI, system-ui, sans-serif"
    fontSize: "14px"
    fontWeight: 400
    lineHeight: 1.5
  label:
    fontFamily: "Archivo, Segoe UI, system-ui, sans-serif"
    fontSize: "14px"
    fontWeight: 600
    lineHeight: 1.43
  form-label:
    fontFamily: "Archivo, Segoe UI, system-ui, sans-serif"
    fontSize: "12px"
    fontWeight: 700
    lineHeight: 1.17
    letterSpacing: "0.08em"
    fontVariation: "'wdth' 75"
  edge:
    fontFamily: "Martian Mono, ui-monospace, Cascadia Mono, Consolas, monospace"
    fontSize: "10px"
    fontWeight: 500
    lineHeight: 1.2
    letterSpacing: "0.08em"
    fontVariation: "'wdth' 75"
  mono:
    fontFamily: "Martian Mono, ui-monospace, Cascadia Mono, Consolas, monospace"
    fontSize: "13px"
    fontWeight: 400
    lineHeight: 1.38
    fontFeature: "'tnum'"
    fontVariation: "'wdth' 87.5"
  mono-caps:
    fontFamily: "Martian Mono, ui-monospace, Cascadia Mono, Consolas, monospace"
    fontSize: "11px"
    fontWeight: 500
    lineHeight: 1.27
    letterSpacing: "0.06em"
    fontVariation: "'wdth' 75"
  code:
    fontFamily: "Martian Mono, ui-monospace, Cascadia Mono, Consolas, monospace"
    fontSize: "28px"
    fontWeight: 600
    lineHeight: 1.14
    letterSpacing: "0.16em"
    fontVariation: "'wdth' 100"
  wordmark:
    fontFamily: "Archivo, Segoe UI, system-ui, sans-serif"
    fontSize: "23px"
    fontWeight: 800
    lineHeight: 1
    letterSpacing: "-0.01em"
    fontVariation: "'wdth' 82"
rounded:
  frame: "2px"
  print: "4px"
  ticket: "6px"
  sheet: "8px"
  sheet-top: "12px"
spacing:
  xxs: "2px"
  xs: "4px"
  sm: "8px"
  md: "12px"
  lg: "16px"
  xl: "24px"
  xxl: "32px"
  xxxl: "48px"
  screen: "16px"
  touch: "48px"
components:
  button-ink:
    backgroundColor: "{colors.print-ink}"
    textColor: "{colors.photo-paper}"
    typography: "{typography.label}"
    rounded: "{rounded.print}"
    padding: "12px 18px"
    height: "48px"
  button-ink-hover:
    backgroundColor: "{colors.print-ink-hover}"
  button-outline:
    backgroundColor: "transparent"
    textColor: "{colors.print-ink}"
    typography: "{typography.label}"
    rounded: "{rounded.print}"
    padding: "12px 18px"
    height: "48px"
  button-quiet:
    backgroundColor: "transparent"
    textColor: "{colors.print-ink}"
    rounded: "{rounded.print}"
    padding: "12px 12px"
    height: "48px"
  button-danger:
    backgroundColor: "{colors.safelight-red}"
    textColor: "{colors.print-sheet}"
    rounded: "{rounded.print}"
    padding: "12px 18px"
    height: "48px"
  button-on-envelope:
    backgroundColor: "{colors.on-envelope}"
    textColor: "{colors.envelope-yellow}"
    typography: "{typography.label}"
    rounded: "{rounded.print}"
    padding: "12px 18px"
    height: "48px"
  button-desktop:
    rounded: "{rounded.print}"
    padding: "0 14px"
    height: "36px"
  button-desktop-sm:
    padding: "0 10px"
    height: "30px"
  envelope:
    backgroundColor: "{colors.envelope-yellow}"
    textColor: "{colors.on-envelope}"
    rounded: "{rounded.print}"
    padding: "26px 20px 20px"
  ticket-stub:
    backgroundColor: "{colors.envelope-yellow}"
    textColor: "{colors.on-envelope}"
    padding: "8px 20px 20px"
  print-sheet-block:
    backgroundColor: "{colors.print-sheet}"
    textColor: "{colors.print-ink}"
    rounded: "{rounded.print}"
  film-frame:
    backgroundColor: "{colors.film-black}"
    textColor: "{colors.film-ink}"
    rounded: "{rounded.frame}"
    padding: "0 5px"
  status-tag:
    backgroundColor: "transparent"
    textColor: "{colors.print-ink-2}"
    typography: "{typography.edge}"
    rounded: "{rounded.frame}"
    padding: "3px 6px"
    height: "20px"
  status-tag-job:
    backgroundColor: "{colors.envelope-yellow}"
    textColor: "{colors.on-envelope}"
  segmented-selected:
    backgroundColor: "{colors.print-ink}"
    textColor: "{colors.photo-paper}"
    rounded: "{rounded.print}"
  input:
    backgroundColor: "{colors.print-sheet}"
    textColor: "{colors.print-ink}"
    rounded: "{rounded.print}"
    padding: "0 12px"
    height: "36px"
  notice:
    backgroundColor: "{colors.safelight-wash}"
    textColor: "{colors.on-safelight-wash}"
    rounded: "{rounded.print}"
    padding: "8px 8px 8px 16px"
  nav-indicator-android:
    backgroundColor: "{colors.envelope-yellow}"
    textColor: "{colors.on-envelope}"
  nav-item-desktop-current:
    backgroundColor: "{colors.print-ink}"
    textColor: "{colors.photo-paper}"
    rounded: "{rounded.print}"
    height: "40px"
  toast:
    backgroundColor: "{colors.print-ink}"
    textColor: "{colors.photo-paper}"
    rounded: "{rounded.print}"
    padding: "11px 14px"
---

# Design System: Pherry

## Overview

**Creative North Star: "Lab Envelope & Contact Sheet"**

Pherry is a photo lab you own. The phone drops film off; the computer develops and keeps it. Every backup is a job written on a yellow drop-off envelope, and every library, arrival list and history is a darkroom contact sheet: strips of film-black frames with orange edge print naming the frame number and its status. The world is the same on both platforms; Android (Jetpack Compose, Material 3 with its roles remapped) and Pherry Desktop (Electron, vanilla CSS custom properties) are two prints from one negative.

Grounds are photo-print paper by day and darkroom by night. One committed field of envelope yellow per screen holds the job (what is waiting, sending or selected), with ink-black controls printed on it. Everything else is flat: paper, white print sheets, hairline rules, square 4px corners, perforated tear lines and the envelope's thumb-cut notch. Depth comes from material and colour, not shadow. Safelight red appears only for rejects and deletions; a small green lamp only for a live link. Phosphor icons, Archivo for words, Martian Mono for data.

The system rejects the cloud-sync dashboard: no stat-card grids, gradient progress bars, cloud glyphs or teal. The metaphor is visual only; the words stay literal.

**Key Characteristics:**
- One envelope-yellow field per screen, and it always means "the job".
- Film-black strips with orange edge print carry every collection of photos.
- Flat paper and darkroom grounds; square 4px print corners, 2px frame corners.
- Selection is a yellow grease-pencil ring drawn on the picture, never a chrome overlay.
- Arrivals develop from an orange negative into the print.
- Archivo (variable width) for words, Martian Mono for data; both bundled, never fetched.

## Colors

A neutral paper-and-ink print world with one committed yellow, one orange that lives only on film, and two signal colours rationed to their meaning.

### Primary
- **Envelope Yellow** (envelope-yellow): the job field. Home's envelope, the live job band on the desktop Receiver, the selection ticket, the pairing-code stub, the Android nav indicator, the job status tag, the grease-pencil ring, the switch's on state. Identical in light and dark. Pressed state is envelope-yellow-pressed. With Android dynamic colour on, only this role follows the wallpaper (M3 primaryContainer); paper, ink, rules and red stay Pherry's.
- **Envelope Ink** (on-envelope / on-envelope-2): everything printed on yellow. The secondary tone (#4F4316) is for form labels and data lines on the envelope.

### Secondary
- **Edge-Print Orange** (edge-print-orange): exists only on film. Frame numbers, edge-print status, the frame-number caret. Never on paper.

### Tertiary
- **Safelight Red** (safelight-red; darkroom-red #FF6B66): failures, rejects, deletions. Never Disconnect, which only drops the link and keeps the pairing (quiet ink). Its wash (safelight-wash; darkroom-red-wash) backs error notices.
- **Lamp Green** (lamp-green; darkroom-green #4CC27A): the "linked" lamp and nothing else.

### Neutral
- **Photo Paper** (photo-paper) / **Darkroom** (darkroom): the page ground on both platforms; also the window and splash background.
- **Print Sheet** (print-sheet; darkroom-sheet): raised print surfaces: settings blocks, the claim ticket body, inputs, dialogs.
- **Well** (well, well-2; darkroom-well, darkroom-well-2): hover fills, info notices, unchecked switch track.
- **Rule** (rule, rule-strong; darkroom-rule, darkroom-rule-strong): hairlines, borders, perforation dots, dashed empty frames, scrollbars.
- **Print Ink** (print-ink, print-ink-2, print-ink-3; darkroom-ink family): text, filled buttons, outlines, selected segments. ink-3 is the quietest legible text (data lines, placeholders).
- **Film Black** (film-black, film-2, film-rule, film-ink): the strip, the empty frame well, sprocket holes, dim edge text.
- **Desktop-only:** rail (sidebar ground, #EEEEEA / darkroom-rail #11100F), print-ink-hover (#2E2C29 light, #FFFFFF dark) for the ink button's hover, on-red (#FFFFFF light, #1A0505 dark), scrim (rgba(21,20,18,0.42) light, rgba(0,0,0,0.6) dark).

### Platform differences (code is truth)
| Token | Android light / dark | Desktop light / dark |
|---|---|---|
| ink-3 | #66635D / #908C84 | #66635D / #908C84 (same) |
| film | #121110 / #0B0A09 | #121110 / #0B0A09 (same) |
| film-2 | #1D1B19 in both themes | #1D1B19 / #15140F |
| film-rule | #2C2A27 in both themes | #2C2A27 / #262420 |
| film-ink | #CFCAC0 in both themes | #CFCAC0 / #BDB8AE |
| red-wash (dark) | #3A1513 solid | rgba(255,107,102,0.13) |
| onError | #FFFFFF / #1A0505 | on-red #FFFFFF / #1A0505 |

Android maps Material roles onto the world: primary = ink, onPrimary = paper, primaryContainer = envelope yellow, surface/background = paper, surfaceContainerLowest = sheet (light), surfaceContainer = sheet (dark), surfaceVariant = well, secondaryContainer = well-2, outline = rule-strong, outlineVariant = rule, error = red, errorContainer = red wash, tertiary = green, surfaceTint transparent. New Android code reads PherryTheme.colors, not raw M3 roles.

### Named Rules
**The One Envelope Rule.** Each screen commits exactly one envelope-yellow field, and it holds the job. When the job changes, the yellow moves: while a phone is sending, the desktop Receiver's claim-ticket stub prints quietly (sheet and ink, perforation and notches kept) and the live job band holds the yellow. Yellow elsewhere only marks what is in the job (grease ring, job tag, nav indicator, the switch's on state).

**The Edge Print Stays On Film Rule.** Orange appears only on film-black. On paper, data is ink in mono caps.

**The Rationed Signal Rule.** Red means failure or deletion; green means a live link. Neither decorates, neither fills a region.

**The No Yellow On Yellow Rule.** On envelope surfaces selection, focus and highlights invert to ink (the desktop's ::selection flips to ink on the stub and job band; focus rings take on-envelope).

## Typography

**Display Font:** Archivo variable (with Segoe UI, system-ui)
**Body Font:** Archivo variable, width 100
**Label/Mono Font:** Martian Mono variable (with ui-monospace, Cascadia Mono, Consolas)

**Character:** Archivo's width axis does the work of several families: narrow and heavy for display numbers and titles, condensed caps for the envelope's printed form labels, normal width for running text. Martian Mono is the lab's stamp and edge print: data only, never sentences. Both faces ship inside the apps (res/font, desktop/renderer/fonts) with font-display: block.

### Hierarchy
- **Display** (Archivo 800, width 88, 64sp/64): the envelope's count ("38 waiting"). Desktop's job band count is 40px/800/width 90.
- **Headline** (Archivo 800, width 88, 32sp/36): Android screen titles via ScreenHeader. Desktop view titles are 30px/800/width 90/1.05 (26px under 600px).
- **Title** (Archivo 700, width 90, 20sp/26): dialogs, album top bar. Desktop section titles 17px/750/width 90; modal titles 19px/800/width 92; ticket title 22px/800/width 90.
- **Title small** (Archivo 600, 14sp/20): row titles in settings, ledger file names, notice titles.
- **Body** (Archivo 400, 16/14/12.5sp): running text. Desktop body is 14px/1.5, secondary paragraphs 13.5px in ink-2, max 46 to 58ch.
- **Label** (Archivo 600, 14sp/20): buttons on Android. Desktop buttons 13.5px/650, small buttons 12.5px.
- **Form label** (Archivo 700, width 75, 12sp, 0.08em, caps via style): the envelope's printed fields (ON THIS PHONE, BACKED UP), section headings, pairing-stub labels. Desktop `.label` is 11.5px/700/width 75/0.07em in ink-3.
- **Edge** (Martian Mono 500, width 75, 10sp, 0.08em, caps): edge print on film and status tags. Desktop edge is 9.5px; tags 10px.
- **Mono** (Martian Mono 400, width 87.5, 13sp, tabular): sizes, addresses, counts. Envelope values 15sp.
- **Mono caps** (Martian Mono 500, width 75, 11sp, 0.06em): the data line under a title, guide words, date stamps. Desktop `.view-sub` and `.guide` are 11px, 0.06 to 0.08em.
- **Code** (Martian Mono 600, width 100, 28sp Android / 30px desktop, 0.16em): the pairing code as the ticket number.
- **Wordmark** (Archivo 800, width 82, 23, -0.01em): "Pherry" beside the mark, same cut the brand script shapes into SVG.

### Named Rules
**The Mono Is Data Rule.** Martian Mono sets sizes, counts, addresses, codes, dates and edge print. Running text and buttons are Archivo.

**The Data Line Below Rule.** A title's counts and dates go on a mono-caps line under it. Nothing sits above a heading or a display count: no eyebrow captions. A label that names a value is a form field (label over value) beside its siblings, and an envelope's destination is its TO field.

**The Caps By Style Rule.** Edge print, form labels and data lines are uppercased by style or at the call site; source strings stay sentence case.

## Layout

**Android (phone).** Single column with a 16dp gutter (Spacing.screen) on a 4dp-based scale (2, 4, 8, 12, 16, 24, 32, 48). Every touch target is at least 48dp; settings rows at least 56dp. Shell: top bar with the wordmark and the computer chip; M3 NavigationBar on paper with tonal elevation 0 and a yellow indicator; edge-to-edge with full-screen surfaces (album, viewer, onboarding) handling their own insets. Home's first viewport is the envelope (about 60% of the screen) followed by a scrolling film strip of recent sends. The album contact sheet is rows of FilmRow, 3 columns on phones, 4 at 600dp, 6 at 840dp; Library album strips are 4 columns (6 at 600dp) with sprockets. Rows are separated by paper.

**Desktop.** Grid shell: a 228px rail (rail ground, 1px rule on its right) and a main column capped at 1240px with 30px 36px 48px padding. Under 900px the rail collapses to 64px icons (labels kept for assistive tech, visually hidden); under 600px padding drops to 20px 16px and frames to a 120px minimum. The Receiver is a 316px claim ticket beside the contact sheet (32px gap), pinned only when the whole ticket fits (min-width 1081px and min-height 760px), stacking below 1080px. Contact sheets are auto-fill grids of frames with a 148px minimum (168px on the Receiver), zero column gap so each row reads as one strip, and a 14px row gap of paper. View header: title with its data line below, actions to the right, 24px under it.

**Long lists** carry guide words: a sticky header naming the span in view ("OCT 2 — SEP 28" or the day) with a count on the right, on paper over a 1px rule.

## Elevation & Depth

Flat by material. Depth is conveyed by colour fields (paper, white sheet, yellow envelope, film black) and 1px rules, never by shadow. Android removes tonal elevation (surfaceTint transparent, NavigationBar tonalElevation 0). The only shadows are on transient overlays on the desktop, which float above the window by function.

### Shadow Vocabulary
- **Overlay lift** (`box-shadow: 0 2px 6px rgba(21,20,18,0.10), 0 24px 48px -20px rgba(21,20,18,0.34)`; dark `0 2px 8px rgba(0,0,0,0.5), 0 28px 56px -20px rgba(0,0,0,0.85)`): desktop toasts and confirm dialogs only.
- **Lamp halo** (`box-shadow: 0 0 0 3px color-mix(in srgb, var(--green) 22%, transparent)`; Android draws a 22% green disc at 1.6x radius): the linked lamp, a state signal rather than depth.
- **Grease-ring contrast stroke** (18% black under each pen pass, a little wider than the pass): keeps the ring legible on bright photos; not a depth device.

### Named Rules
**The Flat Print Rule.** Cards, sheets, envelopes, tickets and frames carry no shadow. If something needs to stand apart, change its ground or give it a 1px rule.

## Shapes

Square print corners. Envelopes, buttons, fields, notices, toasts, segmented tabs and settings blocks share the 4px print corner; frames, tags, checkboxes, date stamps and the switch knob are nearly square at 2px; the claim ticket and dialogs open to 6px; Android sheets and the largest containers use 8px and the bottom-sheet top 12px. Only lamps are round.

Recurring silhouettes, all drawn in code:
- **Thumb-cut notch:** a 14dp (desktop 15px) half-circle bitten from the envelope's top centre (Android ThumbCutShape; desktop radial-gradient mask).
- **Perforation:** a row of punched dots, 9px pitch, about 1.5px radius, in rule-strong on paper or on-envelope at 35% on yellow. It separates a ticket's stub and the envelope's order form.
- **Tear line:** the desktop ticket's perforation between sheet and yellow stub with a half-circle notch bitten from each side.
- **Sprockets:** rounded slots (6 by 4, 13 pitch) in film-rule along standalone strips only, never inside dense sheets.
- **Rubber date stamp:** double-ruled 2px box, mono caps, rotated -1.5 degrees.
- **Empty strip:** four dashed 4:3 frames (1.5 stroke, rule-strong; Android 48dp wide with 5/4 dashes, desktop 64px).
- Borders are 1px for rules and print sheets, 1.5px for interactive outlines (buttons, inputs, segmented tabs, switch track).

## Components

### Buttons (print buttons)
Printed in ink, square, decisive.
- **Shape:** print corner (4px), 1.5px ink outline on outlined variants.
- **Ink (primary):** ink fill, paper text. Android at least 48dp tall, 18 by 12dp padding, labelLarge, optional 18dp Phosphor icon. Desktop 36px tall (30px small), 0 14px, 13.5px/650.
- **On envelope:** the same styles printed in envelope ink: filled = on-envelope with yellow text; outline = on-envelope stroke; hover on desktop is an 8% ink wash.
- **Outline / Quiet:** transparent with ink stroke; quiet drops the stroke and uses ink-2, hover fills well.
- **Danger / Danger outline:** red fill with on-red text, or red stroke and red text with red-wash hover. Only for deletion.
- **Hover / Focus / Active (desktop):** ink hover to ink-hover; outline hover to well; 1px press; focus is a 2px ink outline at 2px offset; disabled at 40% opacity. Transitions 120ms on cubic-bezier(0.2, 0, 0, 1).
- **Copy:** names the action and, when useful, the count ("Back up 38 now", "Send 12", "Retry 3").

### Status tags
Mono caps in a hairline box: SAVED, SENDING, WAITING, FAILED, ON COMPUTER, 2 NEW.
- **Style:** 2px corner, 1px currentColor border, edge typography, 20px tall on desktop, 3 by 6 padding on Android.
- **Variants:** saved (ink), job (yellow fill, on-envelope text), waiting (ink-2), reject (red), quiet (ink-3, dashed on desktop).

### Lamps
A 10dp (desktop 9px) round light. On = green with halo (ringed in envelope ink when on yellow); busy = yellow blinking, ringed in ink on paper (desktop steps-blink over 1.1s; Android 550ms reverse fade); off = red; idle = hollow ink-3 ring. The computer chip in Android's top bar is a 1px-ruled 4dp box with lamp plus the computer name in mono.

### Cards / Containers (print sheets)
- **Corner Style:** 4px (ticket 6px).
- **Background:** print sheet on paper.
- **Shadow Strategy:** none (see Elevation).
- **Border:** 1px rule.
- **Internal Padding:** 16 to 22px; rows inside separated by 1px hairlines. No icon-tile cards, no stat tiles.

### Inputs / Fields
- **Style:** sheet ground, 1.5px ink-3 stroke, 4px corner, 36px tall on desktop (mono variant for paths and addresses); Android OutlinedTextField with the square 4dp shape and ink focus colours.
- **Focus:** stroke steps ink-3, ink-2 on hover, ink on focus. No glow.
- **Error:** red supporting text under the field, in words that say what to do next.

### Selection controls
- **Envelope checkbox (Android):** order-form box, 20dp, 2dp corner, 1.75dp on-envelope stroke; checked fills on-envelope with a yellow bold check; the whole row toggles; disabled rows print at 45%.
- **Square switch (desktop):** 40 by 22 track, 4px corner, well-2 with a 1.5px ink-3 inset ring; 16px knob, 2px corner. Checked: ink track, yellow knob (light); yellow track, ink knob (dark). Knob slides 18px over 180ms ease-out.
- **Themed M3 switch (Android):** stock Material Switch shape with Pherry colours, matching the desktop: checked is a yellow thumb on an ink track on paper and an ink thumb on a yellow track in the darkroom (pale ink would wash out the yellow thumb); unchecked thumb ink-3 on well-2 with a rule-strong border.
- **Printed segmented tabs:** a 1.5px ink outline split by 1.5px ink dividers; the chosen option prints solid ink with paper text. Desktop 32px tall, 13px/650; Android M3 SingleChoiceSegmentedButtonRow with the 4dp base shape and no check icon.

### Navigation
- **Android:** NavigationBar on paper, yellow pill indicator with on-envelope icon, ink selected label, ink-2 unselected; regular Phosphor icons swap to fill when selected. Transfers uses an M3 SecondaryTabRow with a 2dp ink indicator.
- **Desktop rail:** 40px items, 600 weight, ink-2 text; hover well; current page prints solid ink with paper text and a filled icon. Photo count in condensed mono on the right. The receiver's station (lamp, status word, address) is pinned to the rail foot.

### Envelope (signature)
The job field: envelope yellow, 4px corners, the thumb-cut notch, 20dp padding (plus 6dp at the top under the notch). Content is printed in envelope ink: form fields (label over a mono value: ON THIS PHONE, BACKED UP, and TO, the computer's name behind its link lamp), the waiting count at display size, one ink button, a perforation, order-form checkboxes, and a rubber date stamp for the last backup. Progress is the job bar: a 10dp square ink bar on a 16% ink track, ticked every 10%. Nothing is printed above the count: in message states (no Wi-Fi access, no computer, can't reach it) the lamp sits inline before the title, and the Transfers envelope carries state and destination on the data line under its bar ("FINISHED · TO ALEX-PC · 1.2 GB"). On the desktop the live job band is the same envelope (radial mask notch, 40px count first, then From, Received and Speed fields with the busy lamp before the phone's name, and a 10px job bar) entering with a 420ms rise.

### Film strip and contact sheet (signature)
A film-black strip with 5 to 6px side margins holding equal frames separated by 4dp of film. Each frame: an 18px edge-print row above (frame number plus a bold Phosphor caret, status or time on the right in dim film ink), the picture at 2px corners on a film-2 well (4:3 on desktop sheets, square on Android sheets), and an optional edge row below ("ON PC" with a bold check icon). Video frames carry a 20dp play badge on 72% film; failed frames a red 20dp X badge. Desktop frames are buttons: hover brightens the image 8%, focus is a 2px yellow inset outline. Loading states are blank film-2 frames that breathe slowly (opacity 1 to 0.55, 1.4s, alternate), never spinners, shimmer gradients or placeholder glyphs; a file glyph appears only on a frame that cannot get a thumbnail or whose thumbnail failed.

### Develop animation (signature)
Arriving frames develop from an orange negative into the print. Android drives it from real upload progress through a colour matrix (smoothstep eased, filter removed at 100%). Desktop runs a 1600ms keyframe from an inverted sepia negative on a #3A1E08 ground to the print, on cubic-bezier(0.16, 1, 0.3, 1), resuming mid-develop across re-renders via a negative delay. Reduced motion: a 300ms crossfade.

### Grease-pencil ring (signature)
Selecting a frame draws a yellow china-marker ring over 240ms: a slightly tilted oval (-3 to -7 degrees, about 0.39 by 0.36 of the frame) with a lumpy radius from three low harmonics (5 to 8% in all). The pen lands just outside the line, comes round inside its start, crosses it and drifts 11 to 14% outward before it lifts, so start and tail form a visible X. Weight is four nested round-capped passes up to 3.5dp, heaviest through the middle and tapering in the tail. Every choice is seeded from the selection number, so a pick always draws the same ring. An 18dp yellow 2dp-corner badge holds the number in edge type. No checkmark overlays, no dimming.

### Ticket (signature)
- **Selection ticket (Android):** a full-width yellow band rising from the bottom while frames are marked: perforation along the top, clear (X), "12 selected" in titleMedium (polite live region), a mono-caps detail line ("48 MB · to ALEX-PC"), and the send button printed on the envelope (or an outline "Pair a computer" hint when there is no link).
- **Claim ticket (desktop Receiver):** a white sheet (6px corners) holding the QR on pure white, then a tear line, then the yellow stub with the pairing code as the ticket number (code typography), the address with a Copy button, and a note under a 20% ink hairline. While a job is live the stub goes quiet (.ticket.is-quiet: sheet ground, ink and ink-2 text, rule hairline) so the job band stays the one yellow field. Under 1080px the stub sits beside the body behind a dashed rule. Settings repeats the stub at small size.

### Notices, empties, guide words, toasts
- **Notice:** red wash with a warning-circle icon, a plain title and detail, and the fix as a text button; info variant on well.
- **Empty strip:** the dashed empty frames, a title and one sentence, optionally one action.
- **Guide words:** sticky mono-caps span header on paper over a 1px rule.
- **Toast / snackbar:** ink ground, paper text, 4px corner (red for errors); Android uses snackbars, never Toasts.

### Brand mark
One geometry generates every asset (docs/brand/build_brand.py): a "P" cut from film, its stem a strip with five sprocket perforations and its bowl a rectangular frame window, in ink on an envelope-yellow tile (tile radius 22.2% of the side, mark at 0.86 scale). Outputs: tile mark and logo SVG, a currentColor mono mark, the Archivo 800/width 82 wordmark shaped to paths, ink and darkroom-text lockups, the desktop pherry-icon.svg, the Android adaptive launcher (yellow background, ink foreground at 0.72, white monochrome mask), the in-app ic_pherry_logo, and the white ic_stat_pherry notification icon. Change the mark only in the script and regenerate; never redraw an asset by hand.

## Do's and Don'ts

### Do:
- **Do** give each screen exactly one envelope-yellow field, holding the job, with everything on it printed in on-envelope ink.
- **Do** put every collection of photos on film-black strips with orange edge print (frame number with a Phosphor caret, status or time dim on the right).
- **Do** mark selection with the yellow grease-pencil ring and its order number, and let arrivals develop from the orange negative (300ms crossfade under reduced motion).
- **Do** use 4px print corners for controls and containers, 2px for frames and tags, 1.5px ink strokes for interactive outlines and 1px rules for structure.
- **Do** set titles in Archivo and put their data line below them in Martian Mono caps; name long lists' span with sticky guide words.
- **Do** write plain, specific, second-person copy in sentence case that names the computer ("your computer" as fallback), names the action and count on buttons, and says what to do next in errors.
- **Do** keep 48dp touch targets on Android and visible 2px focus rings on desktop (yellow on film, ink on paper, on-envelope ink on yellow).
- **Do** generate brand assets only from build_brand.py and ship fonts and icons inside both apps.

### Don't:
- **Don't** build stat-card grids, gradient progress bars, cloud glyphs or anything teal; this is not a cloud-sync dashboard.
- **Don't** use shadows, gradients, glass or glow for depth; desktop toasts and dialogs are the only lifted surfaces.
- **Don't** use red except for failures and deletions, or green except for the linked lamp.
- **Don't** put edge-print orange on paper or Martian Mono on running sentences.
- **Don't** put labels above headings; data lines go below titles.
- **Don't** add sprockets inside dense contact sheets; they belong to standalone strips.
- **Don't** use spinners for loading media; show empty film-2 frames.
- **Don't** use lab puns, exclamation marks, "Oops" or emoji in copy, or invent URLs, speeds or features; the metaphor is visual and the words stay literal.
- **Don't** use androidx material icons or any non-Phosphor icon set, or text glyphs as icons.
