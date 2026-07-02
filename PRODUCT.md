# Product

## Register

product

## Users

Individual tracking personal finances — a solo user managing their own accounts, budgets, transactions, and receipts. They check in regularly (daily/weekly), not as a one-off task. They trust the app with exact money data (DECIMAL(19,4) precision, audit-logged mutations), so correctness and clarity matter more than persuasion.

## Product Purpose

Fintrack helps a user see and control their financial life: track accounts and transactions, stay within budgets in real time, review recurring charges, import statements/receipts into a searchable vault, and read spending trends via analytics. Success looks like a user glancing at a screen and immediately knowing where they stand — no reconciling numbers in their head.

## Brand Personality

Sharp, modern, efficient. Fast and minimal-friction — a power tool, not a lecture. No hand-holding copy, no forced delight. Confidence comes from precision (exact numbers, clear states) rather than decoration.

## Anti-references

Avoid the generic "fintech SaaS" look: navy-and-gold palettes, hero-metric-card templates, gradient text, glassmorphism, tiny uppercase eyebrows over every section, numbered-step scaffolding where there's no real sequence. This is app UI, not a marketing pitch — resist landing-page patterns bleeding into dashboard screens.

## Design Principles

- Numbers are the interface — data density and legibility beat whitespace-for-its-own-sake.
- Every state is explicit — loading, empty, error, and over/under-budget states are designed, not afterthoughts.
- Speed over ceremony — minimize clicks and confirmation friction for routine actions (adding a transaction, checking a balance).
- Trust through precision — exact monetary values, consistent rounding, and clear audit trails reinforced visually, not just in the backend.
- Reuse the existing token system — extend the current HSL/Tailwind design tokens (teal-green primary, light/dark themes) rather than introducing new ad hoc colors; the CI design-token gate enforces this.

## Accessibility & Inclusion

Standard WCAG AA: ≥4.5:1 contrast for body text, ≥3:1 for large text, full keyboard navigation, visible focus states, `prefers-reduced-motion` alternatives for all animation. No color-blind-specific requirement beyond AA, but budget/spending status (over/under/warning) should still prefer combining color with an icon or label where practical since it's cheap and improves clarity for everyone.
