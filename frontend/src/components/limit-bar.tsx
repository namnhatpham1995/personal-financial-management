"use client";

interface LimitBarProps {
  spent: string;
  limit: string;
  overBudget: boolean;
}

/**
 * Renders a used-vs-limit progress bar.
 * Under limit: blue (used) on gray (unused).
 * Over limit: green (up to limit) + red (overflow), scaled to total spent.
 */
export function LimitBar({ spent, limit, overBudget }: LimitBarProps) {
  const spentNum = Number(spent);
  const limitNum = Number(limit);

  if (!overBudget) {
    const pct = limitNum > 0 ? Math.min((spentNum / limitNum) * 100, 100) : 0;
    return (
      <div className="h-1.5 w-full overflow-hidden rounded-full bg-muted">
        <div
          className="h-full rounded-full bg-blue-500 transition-all"
          style={{ width: `${pct}%` }}
        />
      </div>
    );
  }

  // Over limit: green = limit portion, red = overflow portion
  const greenPct = spentNum > 0 ? Math.round((limitNum / spentNum) * 100) : 0;
  const redPct = 100 - greenPct;
  return (
    <div className="flex h-1.5 w-full overflow-hidden rounded-full">
      <div className="h-full bg-green-500" style={{ width: `${greenPct}%` }} />
      <div className="h-full bg-red-500" style={{ width: `${redPct}%` }} />
    </div>
  );
}
