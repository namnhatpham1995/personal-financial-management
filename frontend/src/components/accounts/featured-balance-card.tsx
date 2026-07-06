import type { BalancesSummary, CurrencyBalance } from "@/services/analytics-service";
import { formatCurrency } from "@/lib/utils";

interface FeaturedBalanceCardProps {
  balances: CurrencyBalance[];
  /** Selected main currency for the converted grand total; null while balances are still loading. */
  mainCurrency: string | null;
  onMainCurrencyChange: (currency: string) => void;
  /** Converted total for mainCurrency; null when single-currency (no conversion needed) or still loading. */
  summary: BalancesSummary | null;
  isSummaryLoading: boolean;
}

/**
 * The single drenched evergreen element inside the app — Overview's one
 * exception to the restrained interior, per the Reserve Gold gold-shell-only
 * rule (this card uses the brand evergreen, never gold).
 */
export function FeaturedBalanceCard({
  balances,
  mainCurrency,
  onMainCurrencyChange,
  summary,
  isSummaryLoading,
}: FeaturedBalanceCardProps) {
  if (balances.length === 0) return null;

  const isMultiCurrency = balances.length > 1;
  const sortedBalances = [...balances].sort((a, b) => a.currency.localeCompare(b.currency));
  const excludedCurrencyCodes = new Set(
    (summary?.excludedCurrencies ?? []).map((ex) => ex.currency)
  );

  // Single currency: no conversion needed, no dropdown — the native total is the whole picture.
  if (!isMultiCurrency) {
    const [only] = balances;
    return (
      <div className="rounded-lg bg-reserve p-6 text-ivory shadow-card">
        <p className="text-sm font-medium text-ivory/70">Total balance</p>
        <p className="mt-2 font-mono text-3xl font-bold tabular-nums sm:text-4xl">
          {formatCurrency(only.totalBalance, only.currency)}
        </p>
      </div>
    );
  }

  return (
    <div className="rounded-lg bg-reserve p-6 text-ivory shadow-card">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-sm font-medium text-ivory/70">Total balance</p>
        <select
          value={mainCurrency ?? ""}
          onChange={(e) => onMainCurrencyChange(e.target.value)}
          className="rounded-md border border-ivory/20 bg-reserve-deep/50 px-2 py-1 text-sm text-ivory focus:outline-none focus:ring-2 focus:ring-ivory/40 transition-colors"
          aria-label="Main currency"
        >
          {sortedBalances.map((b) => (
            <option key={b.currency} value={b.currency} className="text-foreground">
              {b.currency}
            </option>
          ))}
        </select>
      </div>

      {isSummaryLoading || !summary ? (
        <p className="mt-2 font-mono text-3xl font-bold tabular-nums text-ivory/60 sm:text-4xl">
          {formatCurrency(0, mainCurrency ?? "")}
        </p>
      ) : (
        <p className="mt-2 font-mono text-3xl font-bold tabular-nums sm:text-4xl">
          {formatCurrency(summary.convertedTotal, summary.targetCurrency)}
        </p>
      )}

      {summary?.ratesUnavailable && (
        <p className="mt-2 text-xs text-ivory/70">
          Live rates unavailable for some currencies — totals below show native amounts.
        </p>
      )}

      <div className="mt-4 flex flex-wrap gap-x-6 gap-y-1 border-t border-ivory/15 pt-3">
        {sortedBalances.map((bucket) => (
          <p key={bucket.currency} className="text-sm text-ivory/80">
            <span className="font-mono tabular-nums">
              {formatCurrency(bucket.totalBalance, bucket.currency)}
            </span>
            {excludedCurrencyCodes.has(bucket.currency) && (
              <span className="ml-1 text-ivory/50">(not converted)</span>
            )}
          </p>
        ))}
      </div>
    </div>
  );
}
