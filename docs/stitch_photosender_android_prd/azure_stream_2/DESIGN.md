# Design System Document: The Azure Stream Aesthetic

## 1. Overview & Creative North Star: "The Precision Pilot"
This design system moves away from the "generic SaaS" look to embrace the **Precision Pilot**—a philosophy that treats a desktop utility as a high-end instrument rather than a basic web page. 

The goal is to provide a "heads-up display" feel for PhotoSender, where high-density information is organized through **intentional asymmetry** and **tonal depth** rather than rigid lines. We break the template look by layering surfaces like stacked sheets of optical glass, ensuring that even in high-density views, the user feels a sense of calm, professional "flow."

---

## 2. Colors & Surface Philosophy
The "Azure Stream" aesthetic relies on a monochromatic blue core supported by sophisticated neutrals.

### The "No-Line" Rule
**Explicit Instruction:** You are prohibited from using 1px solid borders to section off major UI areas. Boundaries must be defined solely through background color shifts.
*   **Method:** Place a `surface-container-low` section directly against a `surface` background. The subtle 2-3% difference in luminance is enough for the human eye to perceive a boundary without the "visual noise" of a stroke.

### Surface Hierarchy & Nesting
Treat the UI as a physical stack. Use the `surface-container` tiers (Lowest to Highest) to denote importance:
*   **App Background:** `surface` (#f7f9fb)
*   **Main Navigation/Sidebar:** `surface-container-low` (#f2f4f6)
*   **Primary Content Area:** `surface-container` (#eceef0)
*   **Floating Utility Panels:** `surface-container-highest` (#e0e3e5)

### The "Glass & Gradient" Rule
To elevate the "utility" feel, use **Glassmorphism** for floating elements (like status overlays or tooltips).
*   **Formula:** `surface-container-lowest` at 80% opacity + `backdrop-blur: 12px`.
*   **Signature Textures:** For primary CTAs, use a linear gradient from `primary` (#0040a1) to `primary-container` (#0056d2) at a 135-degree angle. This adds a "lithic" depth that flat hex codes cannot achieve.

---

## 3. Typography: The Manrope Scale
Manrope is a geometric sans-serif that excels in high-density environments due to its wide apertures and modern proportions.

*   **The Editorial Anchor:** Use `display-sm` or `headline-lg` for main view titles, but apply a negative letter-spacing of `-0.02em` to create a "locked-in," authoritative look.
*   **Information Density:** Use `label-md` and `label-sm` for technical metadata (file sizes, transfer speeds). These should be set in `on-surface-variant` (#424654) to keep the UI from feeling cluttered.
*   **The Status Lead:** Status indicators (Connected/Disconnected) should use `title-sm` with `font-weight: 700` to ensure they are the first thing a user sees upon glancing at the dashboard.

---

## 4. Elevation & Depth
We convey hierarchy through **Tonal Layering** rather than traditional structural shadows.

### The Layering Principle
Depth is achieved by "stacking" the `surface-container` tiers. Place a `surface-container-lowest` card on a `surface-container-low` section to create a soft, natural lift.

### Ambient Shadows & "Ghost Borders"
*   **Floating Elements:** Use a "Precision Shadow." 
    *   *Values:* `0px 4px 20px rgba(25, 28, 30, 0.06)`. Note the low opacity; it should feel like ambient occlusion, not a drop shadow.
*   **The Ghost Border Fallback:** If a border is required for accessibility (e.g., in Dark Mode), use `outline-variant` (#c3c6d6) at **15% opacity**. Never use 100% opaque borders.

---

## 5. Components & Interaction Patterns

### Buttons
*   **Primary:** Gradient fill (`primary` to `primary-container`), `roundness-md` (0.375rem). No border.
*   **Secondary:** `surface-container-high` background with `on-surface` text.
*   **State Change:** On hover, increase the `surface-tint` overlay by 8% rather than changing the base color.

### High-Density Cards & Lists
*   **Strict Rule:** Forbid divider lines. Use vertical white space (Scale 3 or 4: 0.6rem–0.9rem) or a `surface-container-lowest` background shift on hover to separate items.
*   **Status Indicators:** Use a "Glow Pulse." A `primary` dot for "Connected" should have a subtle outer glow (4px blur) of the same color to simulate an active LED.

### Desktop-Specific Components
*   **Transfer Rail:** A persistent, slim vertical or horizontal bar using `surface-container-highest`. It should host "active transfer" chips that use Glassmorphism to float above the content.
*   **Compact Inputs:** For desktop utility, use `body-sm` for input text to allow for more fields in a single view. Inputs should be "Flushed"—no background, only a bottom `ghost border` that expands to a full `primary` highlight on focus.

---

## 6. Do's and Don'ts

### Do:
*   **Do** use asymmetrical margins. For example, give the left sidebar 3.5rem (Scale 16) of padding but the right utility panel 1.75rem (Scale 8) to create visual interest.
*   **Do** use `primary-fixed-dim` for "inactive but important" states.
*   **Do** rely on `on-surface-variant` for secondary labels to maintain a clear "information hierarchy."

### Don't:
*   **Don't** use pure black (#000000). Always use `on-surface` (#191c1e) for text to maintain the "Azure Stream" softness.
*   **Don't** use standard "Material Blue." Stick strictly to the `primary` (#0040a1) and `primary-container` (#0056d2) tokens provided.
*   **Don't** use heavy shadows. If you can see the shadow clearly, it’s too dark. It should be felt, not seen.

### Accessibility Note:
While we utilize tonal shifts, ensure the contrast between `on-surface` and `surface-container` tiers meets WCAG AA standards. When in doubt, lean on the `outline` token at low opacity to define boundaries for users with low-contrast sensitivity.