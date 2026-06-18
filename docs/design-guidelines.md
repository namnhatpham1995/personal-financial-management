# Design Guidelines

## Overview

Fintrack uses a **dark-first premium fintech design system** targeting a Copilot Money / Wealthfront aesthetic. All visual decisions flow from a single set of CSS custom-property tokens. Behavior, data, and API contracts are strictly separated from presentation.

---

## Color & Token System

Tokens are defined in `frontend/src/app/globals.css` as CSS custom properties on `:root` (dark default). A `.light` class preserves legacy values for a future toggle.

| Token | Value | Usage |
|---|---|---|
| `--background` | deep slate (`222 47% 6%`) | Page background |
| `--surface-raised` | `220 40% 9%` | Card/row raised surface |
| `--surface-overlay` | `220 35% 12%` | Dialogs, dropdowns |
| `--foreground` | near-white | Body text |
| `--primary` | emerald (`160 84% 39%`) | Accent, active states, CTA |
| `--success` | emerald | Income, positive values |
| `--danger` | rose | Expense, negative values, destructive |
| `--neutral` | slate-500 | Transfer, neutral badges |
| `--border` | `220 32% 17%` | All borders |
| `--muted-foreground` | `215 20% 55%` | Secondary/label text |

Tailwind aliases (`bg-background`, `text-primary`, etc.) are registered in `tailwind.config.ts` and map to these tokens.

---

## Typography

| Purpose | Classes |
|---|---|
| Page/section headings | `tracking-tight font-bold text-slate-100` |
| Secondary labels | `text-xs uppercase tracking-wide text-slate-500` |
| Body prose | Inter sans (default) |
| **All monetary values, dates, numeric tables** | `font-mono tabular-nums` |

The mono family (`JetBrains Mono`) is loaded via `next/font/google` in `layout.tsx` and exposed as `var(--font-mono)` / Tailwind `font-mono`.

---

## Surface Treatment

All cards, rows, and panels share this treatment:

```
rounded-xl border border-slate-800/60 bg-slate-900/40 backdrop-blur-sm
```

Interactive surfaces add a hover state:

```
transition-all duration-200 hover:border-emerald-500/30 hover:bg-slate-900/80
```

Never hard-code `bg-white`, `border-gray-200`, or light-mode `*-100 / *-700` color pairs.

---

## Presentation Primitives

Located in `frontend/src/components/ui/`:

| Component | Purpose |
|---|---|
| `card.tsx` | Base surface (optional `interactive` prop for hover lift) |
| `stat-tile.tsx` | Icon + label + mono value KPI tile |
| `badge.tsx` | Variant pill: `income` / `expense` / `transfer` / `neutral` |
| `icon-badge.tsx` | Emerald rounded icon chip |
| `money-text.tsx` | Mono, tabular-nums, semantic color + sign by transaction type |

Screens **must** compose these primitives rather than duplicating long class strings.

---

## Semantic Financial Colors

| State | Tailwind / token | Usage |
|---|---|---|
| Income / positive | `text-emerald-400`, `bg-emerald-500/10`, `border-emerald-500/20` | Amount sign, income badge, active nav |
| Expense / negative | `text-rose-400`, `bg-rose-500/10`, `border-rose-500/20` | Amount sign, expense badge, destructive action |
| Transfer / neutral | `text-blue-400`, `bg-blue-500/10`, `border-blue-500/20` | Transfer badge |
| Warning | `text-amber-500` | Budget 70–99% used |
| Danger | `text-rose-500` | Budget ≥ 100% used |

---

## Chart Theming

Recharts components use the shared `CHART_THEME` constant exported from `cash-flow-chart.tsx`:

- Grid lines: `rgba(148,163,184,0.08)` (slate-400/8)
- Axis ticks: `#64748b` (slate-500), mono font
- Tooltip: `bg rgba(15,23,42,0.95)` + translucent border + `border-radius 0.75rem`
- Series palette: emerald-400 (income), rose-400 (expense), then indigo/amber/sky/violet/orange

---

## Controls

All inputs, selects, textareas:

```
rounded-lg border border-slate-800/60 bg-slate-900/60 px-3 py-2 text-sm text-slate-200
focus:ring-2 focus:ring-emerald-500/40 focus:border-emerald-500/40 transition-colors
```

Primary actions (CTA buttons):

```
rounded-lg bg-emerald-500/10 border border-emerald-500/20 text-emerald-400
hover:bg-emerald-500/20 transition-colors
```

Destructive actions: `bg-rose-500/10 border-rose-500/20 text-rose-400`.
Secondary/cancel: `border-slate-800/60 text-slate-400 hover:bg-slate-800/60`.

---

## Rules

1. **No light-mode color classes** in dashboard or auth screens — no `bg-white`, `text-slate-900`, `*-100 text-*-700` badge pairs, or bare `shadow-sm`.
2. **Mono numerals everywhere money appears** — `font-mono tabular-nums` on every balance, amount, date, and percentage figure.
3. **Use primitives, not copy-pasted strings** — new cards/rows use `Card`; new money figures use `MoneyText`; type badges use `Badge`.
4. **Presentation-only constraint** — styling changes must never touch queries, mutations, form validation logic, routing, or API contracts.
