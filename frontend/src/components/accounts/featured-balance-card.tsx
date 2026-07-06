import type { CurrencyBalance } from "@/services/analytics-service";
import { formatCurrency } from "@/lib/utils";

/**
 * The single drenched evergreen element inside the app — Overview's one
 * exception to the restrained interior, per the Reserve Gold gold-shell-only
 * rule (this card uses the brand evergreen, never gold).
 */
export function FeaturedBalanceCard({ balances }: { balances: CurrencyBalance[] }) {
  if (balances.length === 0) return null;

  const [primary, ...rest] = balances;

  return (
    <div className="rounded-lg bg-reserve p-6 text-ivory shadow-card">
      <p className="text-sm font-medium text-ivory/70">Total balance</p>
      <p className="mt-2 font-mono text-3xl font-bold tabular-nums sm:text-4xl">
        {formatCurrency(primary.totalBalance, primary.currency)}
      </p>

      {rest.length > 0 && (
        <div className="mt-4 flex flex-wrap gap-x-6 gap-y-1 border-t border-ivory/15 pt-3">
          {rest.map((bucket) => (
            <p key={bucket.currency} className="text-sm text-ivory/80">
              <span className="font-mono tabular-nums">
                {formatCurrency(bucket.totalBalance, bucket.currency)}
              </span>
            </p>
          ))}
        </div>
      )}
    </div>
  );
}
