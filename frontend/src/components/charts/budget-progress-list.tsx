import { BudgetProgress } from "@/services/analytics-service";
import { AlertCircle } from "lucide-react";
import Link from "next/link";

const WARN_THRESHOLD = 70;
const DANGER_THRESHOLD = 100;

function barColor(pct: number): string {
  if (pct >= DANGER_THRESHOLD) return "bg-rose-500";
  if (pct >= WARN_THRESHOLD) return "bg-amber-500";
  return "bg-emerald-500";
}

interface Props {
  budgets: BudgetProgress[];
}

export function BudgetProgressList({ budgets }: Props) {
  if (budgets.length === 0) {
    return (
      <p className="text-sm text-slate-500">
        No budgets configured.{" "}
        <Link href="/dashboard/categories" className="text-emerald-400 hover:text-emerald-300 underline underline-offset-2">
          Set one up in Categories.
        </Link>
      </p>
    );
  }

  return (
    <div className="space-y-4">
      {budgets.map((b) => {
        const rawPct = Number(b.percentUsed);
        const clampedPct = Math.min(rawPct, 100);
        return (
          <div key={b.budgetId}>
            <div className="mb-1.5 flex items-center justify-between text-sm">
              <span className="flex items-center gap-1.5 font-medium text-slate-200">
                {rawPct >= DANGER_THRESHOLD && (
                  <AlertCircle className="h-3.5 w-3.5 text-rose-400" />
                )}
                {b.budgetName}
              </span>
              <span className="font-mono tabular-nums text-xs text-slate-500">
                {b.spent} / {b.limitAmount}
              </span>
            </div>
            <div className="h-1.5 overflow-hidden rounded-full bg-slate-800">
              <div
                className={`h-full rounded-full transition-all ${barColor(rawPct)}`}
                style={{ width: `${clampedPct}%` }}
              />
            </div>
          </div>
        );
      })}
    </div>
  );
}
