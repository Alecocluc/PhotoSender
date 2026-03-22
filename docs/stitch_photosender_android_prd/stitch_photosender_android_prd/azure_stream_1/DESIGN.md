# High-End Android Media Transfer Design System

This document outlines a bespoke, editorial-inspired implementation of Material Design 3. We are moving away from the "generic utility" look of standard file transfer apps and toward a "Digital Concierge" experience. This system prioritizes depth, tonal layering, and sophisticated typography to instill trust and a sense of premium performance.

---

## 1. Creative North Star: "The Digital Curator"
The system is built on the philosophy of **The Digital Curator**. Media transfer shouldn't feel like moving data packets; it should feel like handling precious assets. 

We break the standard Android "box-and-line" template through:
*   **Intentional Asymmetry:** Using varying padding and staggered grid alignments to create a rhythmic flow.
*   **Tonal Depth:** Replacing harsh borders with soft, nested elevation levels.
*   **Editorial Scale:** Using high-contrast typography sizes (Display vs. Body) to create an authoritative hierarchy.

---

## 2. Colors & Surface Philosophy

Our palette is rooted in professional blues and functional status indicators, but executed with a "No-Line" mandate.

### The "No-Line" Rule
**Strict Prohibition:** Do not use 1px solid borders (`outline`) for sectioning content. 
Boundaries must be defined solely through background color shifts. For example, a `surface-container-low` component should sit on a `surface` background. If you feel the need for a line, you haven't used your surface tiers correctly.

### Surface Hierarchy & Nesting
Treat the UI as a series of physical layers—stacked sheets of frosted glass.
*   **Base:** `surface` (#fbf8fe)
*   **Level 1 (Sections):** `surface-container-low` (#f6f2f8)
*   **Level 2 (Cards/Interactive):** `surface-container` (#f0edf2)
*   **Level 3 (High Prominence):** `surface-container-highest` (#e4e1e7)

### The "Glass & Gradient" Rule
To elevate the app from a tool to an experience:
*   **Glassmorphism:** Use semi-transparent `surface-container-lowest` (#ffffff at 80% opacity) with a `backdrop-blur` of 20px for floating headers or navigation bars.
*   **Signature Textures:** For primary action buttons or progress hero sections, use a subtle linear gradient: `primary` (#0040a1) to `primary-container` (#0056d2). This provides a "soul" and depth that flat hex codes lack.

---

## 3. Typography: Editorial Authority

We use a dual-font system to balance character with readability.

*   **Display & Headlines (Manrope):** This is our "Editorial" voice. Use `display-lg` (3.5rem) for empty states and `headline-sm` (1.5rem) for section titles. The wide aperture of Manrope conveys modern, high-tech reliability.
*   **Body & Labels (Inter):** This is our "Functional" voice. Inter’s tall x-height ensures that IP addresses and file names remain legible even at `body-sm` (0.75rem).

**Hierarchy Principle:** Always jump at least two tiers in the scale to show intent. Don't put `title-md` next to `body-lg`; the contrast is too low. Pair `headline-sm` with `body-md` for a crisp, professional look.

---

## 4. Elevation & Depth

### The Layering Principle
Forget shadows for standard cards. Achieve lift by "stacking" tokens. 
*   **Layout Example:** A `surface-container-lowest` card placed on top of a `surface-container-low` background creates a soft, natural lift that feels integrated into the OS.

### Ambient Shadows
If an element must float (e.g., a Floating Action Button), use **Ambient Shadows**:
*   **Shadow Color:** Use a tinted version of `on-surface` (4% to 8% opacity).
*   **Blur:** Minimum 16px to 32px. Avoid "tight" shadows; they look dated and "cheap."

### The "Ghost Border" Fallback
For accessibility in input fields (IP addresses), use a "Ghost Border": `outline-variant` (#c3c6d6) at **20% opacity**. It should be felt, not seen.

---

## 5. Components & Media Styling

### Grid-Based Media Galleries
*   **Styling:** Forbid dividers. Use **Spacing 1** (0.25rem) as a "gutter" between images.
*   **Rounding:** Apply `rounded-md` (0.75rem) to all media thumbnails.
*   **Asymmetry:** In "Featured" folders, use a staggered grid where the first item spans two columns to break the monotony.

### IP Address Inputs
*   **Structure:** Use `surface-container-highest` as the background.
*   **Typography:** Force `title-md` (Inter) for the digits to ensure clarity.
*   **State:** When focused, use a `primary` (2pt) bottom-only glow rather than a full bounding box.

### Status Indicators (The "Pulse")
Do not just use static colored dots. 
*   **Connected (Green):** `tertiary` (#005136) with a subtle outer glow using `tertiary-container`.
*   **Connecting (Yellow):** `on-tertiary-fixed-variant` (#005236) using a slow breathing animation (opacity 40% to 100%).
*   **Disconnected (Red):** `error` (#ba1a1a).

### Progress Indicators
*   **Track:** Use `surface-container-highest`.
*   **Indicator:** A gradient transition from `primary` to `secondary`.
*   **Animation:** Use a "Standard Easing" (Cubic Bezier 0.4, 0, 0.2, 1) to make the movement feel organic.

---

## 6. Do’s and Don’ts

### Do
*   **DO** use whitespace as a separator. If two elements feel cluttered, increase spacing to **Spacing 8** (2rem) instead of adding a line.
*   **DO** use `surface-bright` for dark mode surfaces to prevent "crushed blacks" and maintain a premium OLED feel.
*   **DO** align text-heavy headers to the left with a generous **Spacing 6** (1.5rem) left-margin to create a strong vertical axis.

### Don't
*   **DON'T** use 100% black (#000000). Use `on-surface` (#1b1b1f) for text to maintain a soft, paper-like contrast.
*   **DON'T** use `rounded-none`. Everything in this system has a minimum of `rounded-sm` (0.25rem) to feel approachable.
*   **DON'T** use "Standard" Material blue. Always use our specific `primary` (#0040a1) which has a deeper, more professional saturation.