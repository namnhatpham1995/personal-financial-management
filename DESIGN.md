---
name: Fintrack
description: Personal finance dashboard — quiet wealth, private-banking calm
colors:
  reserve: "oklch(0.30 0.055 165)"
  reserve-deep: "oklch(0.24 0.05 165)"
  ivory-on-green: "oklch(0.97 0.005 160)"
  gold: "oklch(0.83 0.115 88)"
  gold-deep: "oklch(0.72 0.12 85)"
  background-light: "oklch(1 0 0)"
  background-dark: "oklch(0.15 0.014 165)"
  surface-raised-light: "oklch(0.985 0.004 165)"
  surface-raised-dark: "oklch(0.195 0.018 165)"
  surface-overlay-light: "oklch(0.965 0.007 165)"
  surface-overlay-dark: "oklch(0.22 0.02 165)"
  hover-surface-light: "oklch(0.95 0.008 165)"
  hover-surface-dark: "oklch(0.24 0.022 165)"
  foreground-light: "oklch(0.20 0.02 165)"
  foreground-dark: "oklch(0.95 0.004 160)"
  muted-foreground-light: "oklch(0.46 0.02 165)"
  muted-foreground-dark: "oklch(0.66 0.015 160)"
  primary-light: "oklch(0.38 0.07 165)"
  primary-dark: "oklch(0.50 0.09 165)"
  primary-foreground: "oklch(0.97 0.005 160)"
  border-light: "oklch(0.80 0.008 165)"
  border-dark: "oklch(0.28 0.02 165)"
  destructive: "oklch(0.50 0.20 18)"
  destructive-foreground: "oklch(0.97 0.01 20)"
  income-light: "oklch(0.46 0.14 152)"
  income-dark: "oklch(0.78 0.12 150)"
  expense-light: "oklch(0.58 0.20 18)"
  expense-dark: "oklch(0.72 0.17 18)"
  transfer-light: "oklch(0.52 0.19 260)"
  transfer-dark: "oklch(0.70 0.15 255)"
typography:
  display:
    fontFamily: "Newsreader, Georgia, serif"
    fontWeight: 500
    letterSpacing: "-0.01em"
  body:
    fontFamily: "Inter, sans-serif"
    fontSize: "1rem"
    lineHeight: 1.5
  mono:
    fontFamily: "Spline Sans Mono, Menlo, monospace"
    fontFeature: "tabular-nums"
rounded:
  sm: "calc(1.25rem - 8px)"
  md: "calc(1.25rem - 4px)"
  lg: "1.25rem"
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
    rounded: "{rounded.pill}"
    padding: "13px 28px"
  button-gold:
    backgroundColor: "{colors.gold}"
    textColor: "{colors.reserve-deep}"
    rounded: "{rounded.pill}"
    padding: "14px 30px"
  input:
    backgroundColor: "{colors.card}"
    textColor: "{colors.foreground}"
    rounded: "{rounded.md}"
    padding: "11px 14px"
  badge:
    rounded: "{rounded.pill}"
    padding: "2px 8px"
---

# Design System: Fintrack

## 1. Overview

**Creative North Star: "Reserve Gold"**

Fintrack is a precision instrument for money that signals quiet wealth, not a marketing surface shouting for attention. The system leans on a single deep evergreen brand color for its outward-facing shell (landing, auth) and stays disciplined and neutral for the daily-use interior, where a brighter action-green carries buttons, focus, and navigation. Gold — the one metal in the system — is confined entirely to the brand shell; it never appears inside an authenticated screen. Numbers carry the hierarchy — tabular mono figures, high-contrast ink — while everything else stays quiet in the background.

The system explicitly rejects the generic fintech-SaaS look: no navy-and-gold, no hero-metric-card templates, no gradient text, no glassmorphism, no uppercase eyebrows stacked over every section. It also rejects unrestricted luxury cliché — gold is not a decorative accent sprinkled anywhere convenient; its scarcity is what keeps it meaningful. Anchors: Rolex, Harrods, Coutts — private banking, not a pitch deck.

**Key Characteristics:**
- Deep evergreen (`oklch(0.30 0.055 165)`) drenches the brand shell (landing hero, auth header); a brighter action-green (`oklch(0.38 0.07 165)` light / `oklch(0.50 0.09 165)` dark) carries all interior primary actions and active states
- Gold (`oklch(0.83 0.115 88)`) is a hard-scoped brand-shell-only accent — zero gold inside Overview, Transactions, Categories, Vault, or Activity
- Soft-lifted cards at rest (ambient evergreen-tinted shadow + border), never flat, never heavy
- Tabular/mono numerals for every monetary value — alignment is non-negotiable
- Light theme: pure white interior; dark theme: deep green-black — both are first-class, system-default, neither is a fallback
- Brand green and income green are distinct tokens used for distinct purposes: green-as-fill (buttons, active nav) is never green-as-text (income amounts)

## 2. Colors

Restrained interior palette: near-neutral surfaces carrying structure, one action-green accent carrying meaning, semantic colors reserved strictly for transaction/budget state — plus a separately-scoped drenched brand shell where evergreen and gold carry full surface area.

### Brand Shell (landing + auth only)
- **Reserve** (`oklch(0.30 0.055 165)`): the drenched background of the landing hero and the auth-card header band. Never used as an interior surface.
- **Reserve Deep** (`oklch(0.24 0.05 165)`): text color on gold fills (e.g. the gold CTA button's label).
- **Gold** (`oklch(0.83 0.115 88)`): the brand-shell CTA fill, wordmark accent, hero italic emphasis. **Hard constraint: SHALL NOT appear on any authenticated dashboard screen or inside any shared presentation primitive.** This is what keeps the system Rolex, not casino.
- **Gold Deep** (`oklch(0.72 0.12 85)`): secondary gold moments within the shell only (e.g. a progress-bar fill inside a landing-page product-peek chip).
- **Ivory on Green** (`oklch(0.97 0.005 160)`): text/wordmark rendered directly on the evergreen shell.

### Interior Primary
- **Action Green** (`oklch(0.38 0.07 165)` light / `oklch(0.50 0.09 165)` dark): primary buttons, active nav state, focus rings, positive/success indicators inside the app. Always paired with ivory text (`oklch(0.97 0.005 160)`) — the mid-luminance saturated green needs light text in both themes for it to read cleanly, not muddy.

### Neutral (interior)
- **Background** (`oklch(1 0 0)` light / `oklch(0.15 0.014 165)` dark): page background.
- **Card / Surface Raised** (`oklch(0.985 0.004 165)` light / `oklch(0.195 0.018 165)` dark): raised surfaces — cards, modals, inputs. Depth comes from the combination of a subtle tint, a visible border, and the resting card shadow — not from a stark background jump (a stark jump risks reading as the banned "cream/sand" AI default).
- **Surface Overlay** (`oklch(0.965 0.007 165)` light / `oklch(0.22 0.02 165)` dark): secondary chrome — sidebar, table headers, badges.
- **Ink** (`oklch(0.20 0.02 165)` light / `oklch(0.95 0.004 160)` dark): primary text.
- **Muted Ink** (`oklch(0.46 0.02 165)` light / `oklch(0.66 0.015 160)` dark): labels, captions, secondary text — never body copy that needs full contrast.
- **Border** (`oklch(0.80 0.008 165)` light / `oklch(0.28 0.02 165)` dark): 1px hairlines on cards, inputs, dividers.

### Semantic (transaction/budget state — text/badge only, never a surface fill)
- **Income** (`oklch(0.46 0.14 152)` light / `oklch(0.78 0.12 150)` dark): credit/income amounts and badges. Deliberately a **distinct token from Action Green** — a button and a gain must never look like the same color doing double duty.
- **Expense** (`oklch(0.58 0.20 18)` light / `oklch(0.72 0.17 18)` dark): debit/expense amounts and badges. Always paired with a leading `−` sign; the state is never conveyed by hue alone.
- **Transfer** (`oklch(0.52 0.19 260)` light / `oklch(0.70 0.15 255)` dark): account-to-account transfer badges.
- **Destructive** (`oklch(0.50 0.20 18)`, theme-invariant, paired with `oklch(0.97 0.01 20)` foreground): destructive actions, over-budget states, errors. Shares the rose hue family with Expense but is calibrated separately since it renders as a solid filled control, not text.

### Named Rules
**The Gold Shell-Only Rule.** Gold renders only on the landing page and the login/register auth-card header. It SHALL NOT appear on any authenticated dashboard screen, and no shared presentation primitive (`Card`, `StatTile`, `Badge`, `MoneyText`, `IconBadge`) may reference the gold token internally.

**The Fill-vs-Text Rule.** Action Green is only ever a fill (buttons, active nav, the one featured balance card on Overview). Income Green is only ever text or a badge. The two are separate tokens precisely so a button can never be mistaken for a gain, or vice versa.

**The One Interior Accent Rule.** Action Green is the only saturated color used for interior UI chrome (buttons, focus, active states). Income/expense/transfer in the semantic set are reserved exclusively for transaction direction and budget status — never repurposed as decorative accents elsewhere.

## 3. Typography

**Display Font:** Newsreader (headings, hero, `--font-display`) — a serif carries the "considered, old-money" read at display size; this is a deliberate contrast pairing against the sans body, not a similar-weight swap.
**Body Font:** Inter (`--font-sans`)
**Label/Mono Font:** Spline Sans Mono (`--font-mono`) — reserved for monetary values and data

### Hierarchy
- **Display** (Newsreader, 500, `letter-spacing: -0.01em`): landing hero, page titles, dashboard section headers.
- **Headline** (Newsreader, 500, 1.125–1.5rem): card titles, modal headers, auth-card headline.
- **Title** (Inter, 600, 0.875–1rem): list item primary text, form section labels.
- **Body** (Inter, 400, 1rem, line-height 1.5): paragraph text, descriptions, form helper text.
- **Label** (Inter, 500, 0.75rem, `tracking-wide uppercase`, muted-foreground): stat tile captions, table column headers.
- **Numeric** (Spline Sans Mono, 600–700, `tabular-nums`): every monetary amount — stat tiles, transaction rows, chart tooltips. Alignment across rows is the point; never substitute a proportional font here.

### Named Rules
**The Tabular Money Rule.** Any rendered currency amount uses `font-mono tabular-nums`, full stop. This is what makes columns of numbers scannable at a glance — the system's core promise, unchanged from the prior system.

## 4. Elevation

Consistently soft-lifted: every card and raised surface carries a low-diffusion evergreen-tinted ambient shadow at rest, not just on interaction. Depth is gentle and structural (separating content groups) rather than decorative. Interactive cards deepen the shadow and shift border toward Action Green on hover — a quiet, immediate response with no bounce.

### Shadow Vocabulary
- **card** (`0 1px 3px 0 oklch(var(--reserve) / 0.10), 0 1px 2px -1px oklch(var(--reserve) / 0.06)`): default resting elevation for all cards, stat tiles, modals. Tinted toward the evergreen brand hue rather than neutral gray.
- **card-hover** (`0 4px 6px -1px oklch(var(--reserve) / 0.14), 0 2px 4px -2px oklch(var(--reserve) / 0.08)`): interactive card hover state, paired with `border-primary/30` and `bg-hover-surface`.

### Named Rules
**The Always-Lifted Rule.** No card ever sits perfectly flush with the page background. The ambient `card` shadow is present at rest; only its intensity changes on interaction.

## 5. Components

Tight and confident on the interior; allowed to be loud (within the gold-shell rule) on the brand shell.

### Buttons
- **Shape (interior):** `rounded-full` (pill) — a deliberate softening from the prior system's `rounded-lg`, matching the rounder Reserve Gold geometry
- **Primary (interior):** Action Green background, ivory text, `px-4 py-2.5`, `text-sm font-medium`
- **Primary (brand shell only):** Gold background, Reserve Deep text — landing CTA and nothing else
- **Secondary/Ghost:** `bg-secondary` or transparent, `text-foreground`/`text-muted-foreground`, same radius and padding scale
- **Hover / Focus:** `transition-colors`; focus uses `ring-2 ring-primary/40` — never a color-only focus indicator
- **Disabled:** `opacity-50`, pointer-events off

### Badges
- **Style:** `rounded-full`, `px-2 py-0.5`, `text-[10px] font-medium`, 10%-opacity tinted background + matching 20%-opacity border in the semantic color (income/expense/transfer/neutral)

### Cards / Containers
- **Corner Style:** `rounded-lg` (1.25rem / 20px scale-top; use `rounded-md`/`rounded-sm` for the 16px/12px steps on denser components)
- **Background:** `bg-card` (Surface Raised / dark equivalent)
- **Shadow Strategy:** `shadow-card` at rest, `shadow-card-hover` + `border-primary/30` + `bg-hover-surface` on interactive hover (see Elevation)
- **Border:** 1px `border-border`
- **Internal Padding:** `p-5` to `p-8` depending on density (stat tiles tighter, auth/modal cards looser)

### Inputs / Fields
- **Style:** `rounded-md border border-border bg-card px-3.5 py-2.5 text-base`
- **Focus:** `ring-2 ring-primary/40 border-primary/40`, no glow/shadow, `transition-colors`
- **Placeholder:** `text-muted-foreground/50`
- **Icon buttons inside fields** (e.g. password toggle): `rounded-sm`, `hover:bg-muted`, same focus ring treatment

### Navigation
- Sidebar-driven app shell; active state uses Action Green accent, inactive uses muted-foreground; hover uses `hover-surface`. Nav is regrouped: Overview/Transactions/Vault as primary items, Categories/Activity below a divider as secondary. No top marketing nav on the interior — this is a dashboard shell, not a page-by-page site. Zero gold anywhere in the shell.

### Stat Tile (signature component)
- `IconBadge` (10×10 rounded-lg, action-green-tinted icon chip) + label (`text-xs uppercase tracking-wide muted-foreground`) + value (`font-mono tabular-nums text-xl font-bold`) inside a `Card`. This is the primary at-a-glance data unit throughout the dashboard.

### Brand Shell (landing + auth)
- **Landing hero:** full-bleed Reserve background, Newsreader headline with a gold-italic emphasis word, gold pill CTA, one floating white product-peek "balance chip" (rotated slightly, drop shadow) showing a live-feeling balance + budget bar.
- **Auth card:** Reserve header band (wordmark + headline, gold hairline or wordmark accent) atop a white form body. The gold accent is confined to the header band; the form fields, buttons, and footer links below use only interior tokens (Action Green, neutral).

## 6. Do's and Don'ts

### Do:
- **Do** use `font-mono tabular-nums` for every monetary value, no exceptions.
- **Do** keep Action Green as the only saturated accent for interior buttons, focus rings, and active nav — everything else stays neutral or semantic.
- **Do** confine gold strictly to the landing page and the auth-card header band — check every new component against this before shipping.
- **Do** keep Income Green (text) and Action Green (fill) as separate tokens; never let one page's "positive" button reuse the income color or vice versa.
- **Do** give every card the resting `shadow-card` elevation; never render a flush, shadowless card.
- **Do** reserve income/expense/transfer strictly for their semantics, never as arbitrary decoration.
- **Do** hit ≥4.5:1 contrast for body and placeholder text (WCAG AA baseline from PRODUCT.md); every token pairing above has been computed, not eyeballed — re-verify computationally if a value changes.
- **Do** provide a `prefers-reduced-motion` fallback (instant/crossfade) for any transition beyond simple color/opacity.

### Don't:
- **Don't** use navy-and-gold in the "generic fintech" sense (gold as a decorative accent anywhere and everywhere) — the evergreen+gold system here is deliberately restrained and scoped, not a repeat of that cliché. See PRODUCT.md's anti-references note.
- **Don't** let gold leak into Overview, Transactions, Categories, Vault, Activity, or any shared primitive — this is the single easiest rule to violate by habit; treat any interior gold reference as a rejection in review.
- **Don't** build hero-metric-card templates or landing-page patterns bleeding into the dashboard interior — the drenched treatment is a brand-shell exclusive.
- **Don't** stack tiny uppercase tracked eyebrows over every section; reserve uppercase labels for stat-tile captions and table headers only.
- **Don't** use `border-left`/`border-right` colored stripes as a callout accent anywhere.
- **Don't** introduce a new accent hue outside the token set without updating this file — the CI design-token gate blocks raw Tailwind palette classes for a reason.
- **Don't** use a proportional font for any number a user needs to compare across rows.
