import { BudgetProgress } from "@/services/analytics-service";
import { AlertCircle } from "lucide-react";
import { type ReactNode } from "react";

const WARN_THRESHOLD = 70;
const DANGER_THRESHOLD = 100;

function barColor(pct: number): string {
  if (pct >= DANGER_THRESHOLD) return "bg-rose-500";
  if (pct >= WARN_THRESHOLD) return "bg-amber-500";
  return "bg-emerald-500";
}

interface Props {
  budgets: BudgetProgress[];
  renderActions?: (budget: BudgetProgress) => ReactNode;
  renderDetails?: (budget: BudgetProgress) => ReactNode;
}

export function BudgetProgressList({ budgets, renderActions, renderDetails }: Props) {
  if (budgets.length === 0) {
    return (
      <p className="text-sm text-muted-foreground">No budgets configured. Use Add limit to create one.</p>
    );
  }

  return (
    <div className="space-y-4">
      {budgets.map((b) => {
        const rawPct = Number(b.percentUsed);
        const clampedPct = Math.min(rawPct, 100);
        return (
          <div key={b.budgetId}>
            <div className="mb-1.5 flex items-center justify-between gap-3 text-sm">
              <span className="flex items-center gap-1.5 font-medium text-foreground">
                {rawPct >= DANGER_THRESHOLD && (
                  <AlertCircle className="h-3.5 w-3.5 text-rose-500 dark:text-rose-400" />
                )}
                {b.budgetName}
              </span>
              <div className="flex shrink-0 items-center gap-2">
                <span className="font-mono tabular-nums text-xs text-muted-foreground">
                  {b.spent} / {b.limitAmount}
                </span>
                {renderActions?.(b)}
              </div>
            </div>
            <div className="h-1.5 overflow-hidden rounded-full bg-secondary">
              <div
                className={`h-full rounded-full transition-all ${barColor(rawPct)}`}
                style={{ width: `${clampedPct}%` }}
              />
            </div>
            {renderDetails?.(b)}
          </div>
        );
      })}
    </div>
  );
}
