---
name: Fintrack
description: Personal finance dashboard — precise, sharp, efficient money tracking
colors:
  primary: "hsl(160 84% 26%)"
  primary-foreground: "hsl(160 100% 95%)"
  background: "hsl(220 20% 96%)"
  surface-raised: "hsl(0 0% 100%)"
  surface-overlay: "hsl(214 22% 93%)"
  hover-surface: "hsl(214 24% 91%)"
  foreground: "hsl(222 47% 11%)"
  card: "hsl(0 0% 100%)"
  secondary: "hsl(214 22% 93%)"
  muted: "hsl(214 22% 93%)"
  muted-foreground: "hsl(215 25% 40%)"
  accent: "hsl(214 22% 93%)"
  destructive: "hsl(0 84% 60%)"
  border: "hsl(214 28% 78%)"
  ring: "hsl(160 84% 26%)"
  success: "hsl(160 84% 26%)"
  danger: "hsl(0 84% 60%)"
  income: "rgb(16 185 129)"
  expense: "rgb(244 63 94)"
  transfer: "rgb(59 130 246)"
typography:
  display:
    fontFamily: "Sora, var(--font-sans), sans-serif"
    fontWeight: 600
    letterSpacing: "-0.01em"
  body:
    fontFamily: "Inter, sans-serif"
    fontSize: "1rem"
    lineHeight: 1.5
  mono:
    fontFamily: "JetBrains Mono, Menlo, monospace"
    fontFeature: "tabular-nums"
rounded:
  sm: "calc(0.75rem - 4px)"
  md: "calc(0.75rem - 2px)"
  lg: "0.75rem"
  pill: "9999px"
spacing:
  sm: "0.5rem"
  md: "1rem"
  lg: "1.25rem"
components:
  card:
    backgroundColor: "{colors.card}"
    rounded: "{rounded.lg}"
    padding: "20px"
  card-interactive-hover:
    backgroundColor: "{colors.hover-surface}"
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.primary-foreground}"
    rounded: "{rounded.md}"
    padding: "10px 16px"
  input:
    backgroundColor: "{colors.card}"
    textColor: "{colors.foreground}"
    rounded: "{rounded.md}"
    padding: "8px 12px"
  badge:
    rounded: "{rounded.pill}"
    padding: "2px 8px"
---

# Design System: Fintrack

## 1. Overview

**Creative North Star: "The Clean Register"**

Fintrack is a precision instrument for money, not a marketing surface. The system leans on a single confident teal-green accent against neutral, slightly-cool-tinted surfaces — soft-lifted cards float just enough to separate data groups without ceremony. Every screen exists to answer one question fast: where do I stand? Numbers carry the hierarchy — tabular mono figures, high-contrast ink — while everything else (borders, radii, motion) stays quiet in the background.

The system explicitly rejects the generic fintech-SaaS look: no navy-and-gold, no hero-metric-card templates, no gradient text, no glassmorphism, no uppercase eyebrows stacked over every section. This is app UI that serves the workflow — sharp, modern, efficient, tight and confident in its component feel — not a pitch deck.

**Key Characteristics:**
- One accent color (teal-green, `hsl(160 84% 26%)`) carries all primary actions and positive states
- Soft-lifted cards at rest (ambient shadow + border), never flat, never heavy
- Tabular/mono numerals for every monetary value — alignment is non-negotiable
- Light theme: cool near-white surfaces; dark theme: deep layered navy-black, same accent hue

## 2. Colors

Restrained palette: tinted cool neutrals carrying structure, one saturated accent carrying meaning, semantic colors reserved strictly for transaction/budget state.

### Primary
- **Register Green** (`hsl(160 84% 26%)` light / `hsl(160 84% 39%)` dark): primary buttons, active nav state, focus rings, positive/success indicators. Used sparingly — the one color a user should associate with "go" or "on track". Light-theme L is 26%, not the brighter 32% it started at — that L still cleared 4.5:1 on the app's dark theme but fell to ~3.7-4.2:1 as text-on-tint or text-on-white in light theme; 26% clears AA in both.

### Neutral
- **Cool Paper** (`hsl(220 20% 96%)`): page background, light theme.
- **Deep Ledger** (`hsl(222 47% 6%)`): page background, dark theme.
- **Card White** (`hsl(0 0% 100%)` light / `hsl(220 40% 9%)` dark): raised surfaces — cards, modals, inputs.
- **Surface Overlay** (`hsl(214 22% 93%)` light / `hsl(220 35% 13%)` dark): secondary chrome — sidebar, table headers, badges.
- **Ink** (`hsl(222 47% 11%)` light / `hsl(210 40% 96%)` dark): primary text.
- **Muted Ink** (`hsl(215 25% 40%)` light / `hsl(215 20% 55%)` dark): labels, captions, secondary text — never body copy that needs full contrast.
- **Border** (`hsl(214 28% 78%)` light / `hsl(220 32% 17%)` dark): 1px hairlines on cards, inputs, dividers.

### Semantic (transaction/budget state)
- **Income** (emerald-500, ~10% opacity fill + border): credit/income badges and amounts.
- **Expense** (rose-500, ~10% opacity fill + border): debit/expense badges and amounts.
- **Transfer** (blue-500, ~10% opacity fill + border): account-to-account transfer badges.
- **Danger** (`hsl(0 84% 60%)`): destructive actions, over-budget states, errors.

### Named Rules
**The One Accent Rule.** Register Green is the only saturated color used for UI chrome (buttons, focus, active states). Emerald/rose/blue in the semantic set are reserved exclusively for transaction direction and budget status — never repurposed as decorative accents elsewhere.

## 3. Typography

**Display Font:** Sora (headings, `--font-display`)
**Body Font:** Inter (`--font-sans`)
**Label/Mono Font:** JetBrains Mono (`--font-mono`) — reserved for monetary values and data

**Character:** Sora's geometric confidence for headings paired with Inter's neutral legibility for body text — a contrast pairing, not two similar grotesques. JetBrains Mono breaks the sans rhythm deliberately wherever a number needs to be scanned and compared.

### Hierarchy
- **Display** (Sora, 600, `letter-spacing: -0.01em`): page titles, dashboard section headers.
- **Headline** (Sora, 600, 1.125–1.25rem): card titles, modal headers.
- **Title** (Inter, 600, 0.875–1rem): list item primary text, form section labels.
- **Body** (Inter, 400, 1rem, line-height 1.5): paragraph text, descriptions, form helper text.
- **Label** (Inter, 500, 0.75rem, `tracking-wide uppercase`, muted-foreground): stat tile captions, table column headers.
- **Numeric** (JetBrains Mono, 600–700, `tabular-nums`): every monetary amount — stat tiles, transaction rows, chart tooltips. Alignment across rows is the point; never substitute a proportional font here.

### Named Rules
**The Tabular Money Rule.** Any rendered currency amount uses `font-mono tabular-nums`, full stop. This is what makes columns of numbers scannable at a glance — the system's core promise.

## 4. Elevation

Consistently soft-lifted: every card and raised surface carries a low-diffusion ambient shadow at rest, not just on interaction. Depth is gentle and structural (separating content groups) rather than decorative. Interactive cards deepen the shadow and shift border toward the primary accent on hover — a quiet, immediate response with no bounce.

### Shadow Vocabulary
- **card** (`0 1px 3px 0 rgb(0 0 0 / 0.06), 0 1px 2px -1px rgb(0 0 0 / 0.04)`): default resting elevation for all cards, stat tiles, modals.
- **card-hover** (`0 4px 6px -1px rgb(0 0 0 / 0.08), 0 2px 4px -2px rgb(0 0 0 / 0.06)`): interactive card hover state, paired with `border-primary/30` and `bg-hover-surface`.

### Named Rules
**The Always-Lifted Rule.** No card ever sits perfectly flush with the page background. The ambient `card` shadow is present at rest; only its intensity changes on interaction.

## 5. Components

Tight and confident: compact padding, crisp 1px borders, minimal motion tuned for fast repeated use rather than first impressions.

### Buttons
- **Shape:** `rounded-lg` (`calc(0.75rem - 2px)` ≈ 10px)
- **Primary:** Register Green background, `primary-foreground` text, `px-4 py-2.5`, `text-sm font-medium`
- **Secondary/Ghost:** `bg-secondary` or transparent, `text-foreground`/`text-muted-foreground`, same radius and padding scale
- **Hover / Focus:** `transition-colors`; focus uses `ring-2 ring-primary/40` — never a color-only focus indicator
- **Disabled:** `opacity-50`, pointer-events off

### Badges
- **Style:** `rounded-full`, `px-2 py-0.5`, `text-[10px] font-medium`, 10%-opacity tinted background + matching 20%-opacity border in the semantic color (income/expense/transfer/neutral)

### Cards / Containers
- **Corner Style:** `rounded-xl` (0.75rem)
- **Background:** `bg-card` (Card White / dark equivalent)
- **Shadow Strategy:** `shadow-card` at rest, `shadow-card-hover` + `border-primary/30` + `bg-hover-surface` on interactive hover (see Elevation)
- **Border:** 1px `border-border`
- **Internal Padding:** `p-5` to `p-8` depending on density (stat tiles tighter, auth/modal cards looser)

### Inputs / Fields
- **Style:** `rounded-lg border border-border bg-card px-3 py-2 text-base`
- **Focus:** `ring-2 ring-primary/40 border-primary/40`, no glow/shadow, `transition-colors`
- **Placeholder:** `text-muted-foreground/50`
- **Icon buttons inside fields** (e.g. password toggle): `rounded-md`, `hover:bg-muted`, same focus ring treatment

### Navigation
- Sidebar-driven app shell; active state uses Register Green accent, inactive uses muted-foreground; hover uses `hover-surface`. No top marketing nav — this is a dashboard shell, not a page-by-page site.

### Stat Tile (signature component)
- `IconBadge` (10×10 rounded-lg, emerald-tinted icon chip) + label (`text-xs uppercase tracking-wide muted-foreground`) + value (`font-mono tabular-nums text-xl font-bold`) inside a `Card`. This is the primary at-a-glance data unit throughout the dashboard.

## 6. Do's and Don'ts

### Do:
- **Do** use `font-mono tabular-nums` for every monetary value, no exceptions.
- **Do** keep Register Green (`hsl(160 84% 26%)`) as the only saturated accent for buttons, focus rings, and active nav — everything else stays neutral or semantic.
- **Do** give every card the resting `shadow-card` elevation; never render a flush, shadowless card.
- **Do** reserve emerald/rose/blue strictly for income/expense/transfer semantics, never as arbitrary decoration.
- **Do** hit ≥4.5:1 contrast for body and placeholder text (WCAG AA baseline from PRODUCT.md); bump muted-foreground toward ink if a screen reads too light.
- **Do** provide a `prefers-reduced-motion` fallback (instant/crossfade) for any transition beyond simple color/opacity.

### Don't:
- **Don't** use navy-and-gold, gradient text, or glassmorphism — explicitly rejected in PRODUCT.md's anti-references.
- **Don't** build hero-metric-card templates or landing-page patterns (big number + gradient accent + supporting stats) — this is app UI, not a pitch.
- **Don't** stack tiny uppercase tracked eyebrows over every section; reserve uppercase labels for stat-tile captions and table headers only.
- **Don't** use `border-left`/`border-right` colored stripes as a callout accent anywhere.
- **Don't** introduce a new accent hue outside the token set (primary, income, expense, transfer, danger) without updating this file — the CI design-token gate blocks raw Tailwind palette classes for a reason.
- **Don't** use a proportional font for any number a user needs to compare across rows.
