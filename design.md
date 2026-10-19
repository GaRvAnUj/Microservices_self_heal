---
version: alpha
name: Indeed Minimal
description: A clean, trust-forward job search system with bright white surfaces, vivid blue accents, and understated gray text.
colors:
  primary: "#004FCB"
  secondary: "#595959"
  tertiary: "#E5E7EB"
  neutral: "#FFFFFF"
  surface: "#FFFFFF"
  on-surface: "#595959"
  error: "#D93025"
  primary-60: "#2E6FD6"
  primary-70: "#1C5ECB"
  primary-80: "#0040A8"
  border: "#E5E7EB"
  muted: "#F8F9FA"
typography:
  headline-display:
    fontFamily: "Indeed Sans, Noto Sans, Helvetica Neue, Helvetica, Arial, sans-serif"
    fontSize: "32px"
    fontWeight: 700
    lineHeight: "38px"
    letterSpacing: "0px"
  headline-lg:
    fontFamily: "Indeed Sans, Noto Sans, Helvetica Neue, Helvetica, Arial, sans-serif"
    fontSize: "24px"
    fontWeight: 700
    lineHeight: "30px"
    letterSpacing: "0px"
  headline-md:
    fontFamily: "Indeed Sans, Noto Sans, Helvetica Neue, Helvetica, Arial, sans-serif"
    fontSize: "20px"
    fontWeight: 600
    lineHeight: "24px"
    letterSpacing: "0px"
  headline-sm:
    fontFamily: "Indeed Sans, Noto Sans, Helvetica Neue, Helvetica, Arial, sans-serif"
    fontSize: "18px"
    fontWeight: 600
    lineHeight: "22px"
    letterSpacing: "0px"
  body-lg:
    fontFamily: "Indeed Sans, Noto Sans, Helvetica Neue, Helvetica, Arial, sans-serif"
    fontSize: "16px"
    fontWeight: 400
    lineHeight: "24px"
    letterSpacing: "0px"
  body-md:
    fontFamily: "Indeed Sans, Noto Sans, Helvetica Neue, Helvetica, Arial, sans-serif"
    fontSize: "16px"
    fontWeight: 400
    lineHeight: "normal"
    letterSpacing: "0px"
  body-sm:
    fontFamily: "Indeed Sans, Noto Sans, Helvetica Neue, Helvetica, Arial, sans-serif"
    fontSize: "14px"
    fontWeight: 400
    lineHeight: "20px"
    letterSpacing: "0px"
  label-lg:
    fontFamily: "Noto Sans, Indeed Sans, Helvetica Neue, Helvetica, Arial, sans-serif"
    fontSize: "16px"
    fontWeight: 700
    lineHeight: "22px"
    letterSpacing: "0px"
  label-md:
    fontFamily: "Indeed Sans, Noto Sans, Helvetica Neue, Helvetica, Arial, sans-serif"
    fontSize: "14px"
    fontWeight: 700
    lineHeight: "20px"
    letterSpacing: "0px"
  label-sm:
    fontFamily: "Indeed Sans, Noto Sans, Helvetica Neue, Helvetica, Arial, sans-serif"
    fontSize: "12px"
    fontWeight: 700
    lineHeight: "16px"
    letterSpacing: "0px"
  nav-link:
    fontFamily: "Indeed Sans, Noto Sans, Helvetica Neue, Helvetica, Arial, sans-serif"
    fontSize: "14px"
    fontWeight: 400
    lineHeight: "20px"
    letterSpacing: "0px"
  button-md:
    fontFamily: "Noto Sans, Indeed Sans, Helvetica Neue, Helvetica, Arial, sans-serif"
    fontSize: "16px"
    fontWeight: 700
    lineHeight: "22px"
    letterSpacing: "0px"
rounded:
  none: 0px
  sm: 4px
  md: 8px
  lg: 12px
  xl: 16px
  full: 9999px
spacing:
  xs: 2px
  sm: 8px
  md: 16px
  lg: 22px
  xl: 48px
  gutter: 24px
  section: 48px
components:
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.neutral}"
    typography: "{typography.button-md}"
    rounded: "{rounded.lg}"
    padding: "9px 48px"
    height: "44px"
  button-primary-hover:
    backgroundColor: "{colors.primary-60}"
    textColor: "{colors.neutral}"
    rounded: "{rounded.lg}"
  button-secondary:
    backgroundColor: "transparent"
    textColor: "{colors.secondary}"
    typography: "{typography.button-md}"
    rounded: "{rounded.sm}"
    padding: "9px 48px"
    height: "44px"
  button-link:
    backgroundColor: "transparent"
    textColor: "{colors.secondary}"
    typography: "{typography.body-md}"
    rounded: "{rounded.none}"
    padding: "0px"
  card:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.on-surface}"
    rounded: "{rounded.md}"
    padding: "16px"
  input:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.on-surface}"
    typography: "{typography.body-md}"
    rounded: "{rounded.full}"
    padding: "12px 16px"
  search-bar:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.on-surface}"
    rounded: "{rounded.full}"
    padding: "8px 16px"
    height: "60px"
  nav-link:
    backgroundColor: "transparent"
    textColor: "{colors.secondary}"
    typography: "{typography.nav-link}"
    rounded: "{rounded.none}"
    padding: "8px 12px"
  footer-link:
    backgroundColor: "transparent"
    textColor: "{colors.secondary}"
    typography: "{typography.body-sm}"
    rounded: "{rounded.none}"
    padding: "0px"
  divider:
    backgroundColor: "{colors.border}"
    height: "1px"
  pill:
    backgroundColor: "{colors.muted}"
    textColor: "{colors.secondary}"
    typography: "{typography.label-md}"
    rounded: "{rounded.full}"
    padding: "6px 12px"
# Indeed Minimal

## Overview
Indeed feels calm, utilitarian, and highly trustworthy, with a strong focus on utility over ornament. The interface is spacious and low-friction, designed for job seekers who need clarity and speed. Brand emphasis comes from the saturated blue accent, while everything else stays neutral and unobtrusive.

## Colors
- **Primary (#004FCB):** The signature Indeed blue used for primary actions, active navigation states, and brand moments like the logo and main call-to-action.
- **Secondary (#595959):** A soft charcoal used for body text, utility links, and secondary buttons. It keeps the UI readable without feeling harsh.
- **Neutral / Surface (#FFFFFF):** The dominant canvas color, supporting the bright, open, and approachable feel of the page.
- **Tertiary / Border (#E5E7EB):** A light gray used for dividers, card borders, and subtle structural separation.
- **Muted (#F8F9FA):** A barely tinted background for low-emphasis pills or secondary surface treatments.
- **Primary variants (#2E6FD6, #1C5ECB, #0040A8):** Slightly darker and lighter steps for hover, active, and pressed states while preserving the same visual identity.
- **Error (#D93025):** Reserved for validation and destructive feedback, though it is not prominent in the screenshot.

## Typography
The system uses a sans-serif stack led by Indeed Sans, with Noto Sans as an important fallback for reliable rendering. Headlines are bold and compact, with the largest title set at 32px/38px and smaller hierarchy steps at 24px, 20px, and 18px. Body text is restrained and readable at 16px, while labels and navigation rely on 14px to 16px weights, usually bold for actions and regular for links. Letter spacing is normal and there is no visible uppercase convention; the tone stays straightforward rather than editorial.

## Layout
The page uses a wide, centered layout with generous whitespace and a simple vertical flow. A fixed top navigation bar anchors the experience, while the main hero content is centered under the search module. Spacing follows a sparse rhythm: 2px for tight internal nudges, 8px and 16px for most controls, 22px for moderate separation, and 48px for section-scale breathing room. Cards and input groups favor comfortable internal padding rather than dense grids, with the search bar stretched wide to signal primary utility.

## Elevation & Depth
Depth is subtle and functional rather than decorative. The search bar uses a soft shadow and thin border to lift it above the white background, while most other surfaces stay flat with minimal layering. Cards depend on a 1px light-gray border and white fill instead of strong shadows, creating a clean, restrained hierarchy. Active states are communicated more through color contrast than through heavy elevation.

## Shapes
The shape language is soft but controlled. Primary buttons and key inputs use larger radii, especially the 12px to full-pill treatment visible in the search and CTA controls. Secondary buttons are slightly firmer with a 4px radius, which keeps them distinct without feeling boxy. Overall, the system balances friendly curves with practical, enterprise-style restraint.

## Components
Buttons are the clearest expression of hierarchy. `button-primary` uses a filled blue background, white text, bold 16px type, and a 44px minimum height; it should feel prominent, confident, and action-oriented. `button-secondary` is outlined and quieter, with transparent fill, gray text, and a smaller 4px radius. `button-link` is purely textual, intended for low-emphasis actions like sign-in or utility links.

Inputs and search controls should appear as a single unified bar when used in hero search contexts. `search-bar` and `input` use white backgrounds, subtle borders, rounded corners, and generous horizontal padding so icons and text breathe. Segment dividers inside composite inputs should remain light and unobtrusive.

Cards should follow the `card` treatment: white background, 1px light border, 8px radius, and 16px padding. They should feel like structured containers, not floating panels. Pills and small status elements can use `pill` for quiet emphasis with a muted background and rounded-full shape.

Navigation links are understated, text-first items with a medium-small size and no decorative framing. Active nav states should be communicated with the primary blue and a thin underline or similar minimal indicator. Footers should stay compact, gray, and link-heavy without introducing strong visual weight.

## Do's and Don'ts
- Do keep the interface bright, spacious, and low-noise.
- Do use the primary blue sparingly for the most important actions and active states.
- Do prefer thin borders and light shadows over heavy elevation.
- Do keep typography simple, sans-serif, and highly legible.
- Don't introduce elaborate gradients, textures, or decorative imagery.
- Don't overuse rounded pills on secondary or structural UI.
- Don't make body text too dark or too large; preserve the calm, utility-first tone.
- Don't add dense card shadows or complex overlays that fight the minimalist layout.