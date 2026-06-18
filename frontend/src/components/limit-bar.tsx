"use client";

interface LimitBarProps {
  spent: string;
  limit: string;
  overBudget: boolean;
}

/**
 * Renders a used-vs-limit progress bar on dark surfaces.
 * Under limit: emerald fill on slate track.
 * Over limit: emerald (up to limit) + rose (overflow), scaled to total spent.
 */
export function LimitBar({ spent, limit, overBudget }: LimitBarProps) {
  const spentNum = Number(spent);
  const limitNum = Number(limit);

  if (!overBudget) {
    const pct = limitNum > 0 ? Math.min((spentNum / limitNum) * 100, 100) : 0;
    return (
      <div className="h-1.5 w-full overflow-hidden rounded-full bg-slate-800">
        <div
          className="h-full rounded-full bg-emerald-500 transition-all"
          style={{ width: `${pct}%` }}
        />
      </div>
    );
  }

  // Over limit: emerald = limit portion, rose = overflow portion
  const emeraldPct = spentNum > 0 ? Math.round((limitNum / spentNum) * 100) : 0;
  const rosePct = 100 - emeraldPct;
  return (
    <div className="flex h-1.5 w-full overflow-hidden rounded-full">
      <div className="h-full bg-emerald-500" style={{ width: `${emeraldPct}%` }} />
      <div className="h-full bg-rose-500" style={{ width: `${rosePct}%` }} />
    </div>
  );
}
