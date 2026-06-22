"use client";

import { AlertTriangle } from "lucide-react";
import type { RateUsed, ExcludedCurrency } from "@/services/analytics-service";
import { formatRate, formatDate } from "@/lib/utils";

interface RatesUsedNoteProps {
  rates: RateUsed[];
  /** ISO instant string from the backend (e.g. "2024-06-15T10:30:00Z") */
  asOf: string | null;
  /** When true, the rates may be outdated — show a visible warning banner */
  stale: boolean;
  excludedCurrencies: ExcludedCurrency[];
}

/**
 * Passive note showing exchange rates applied to the converted overview.
 * When stale=true, renders a distinct amber warning banner above the rate list.
 * When excludedCurrencies is non-empty, renders per-currency exclusion notices.
 */
export function RatesUsedNote({
  rates,
  asOf,
  stale,
  excludedCurrencies,
}: RatesUsedNoteProps) {
  const asOfLabel = asOf ? formatDate(asOf) : null;

  return (
    <div className="space-y-2 text-xs">
      {/* Stale warning banner — distinct from the passive note below */}
      {stale && (
        <div className="flex items-center gap-2 rounded-lg border border-amber-500/30 bg-amber-500/10 px-3 py-2 text-amber-600 dark:text-amber-400">
          <AlertTriangle className="h-3.5 w-3.5 flex-shrink-0" aria-hidden="true" />
          <span className="font-medium">Exchange rates may be outdated</span>
        </div>
      )}

      {/* Passive "rates as of" note */}
      {asOfLabel && (
        <p className="text-muted-foreground">
          Rates as of <span className="font-medium text-foreground">{asOfLabel}</span>
        </p>
      )}

      {/* Individual rate lines */}
      {rates.length > 0 && (
        <ul className="space-y-0.5">
          {rates.map((r) => (
            <li
              key={`${r.from}-${r.to}`}
              className="font-mono tabular-nums text-muted-foreground"
            >
              {formatRate(r.from, r.to, r.rate)}
            </li>
          ))}
        </ul>
      )}

      {/* Excluded currencies — currencies with no available rate */}
      {excludedCurrencies.length > 0 && (
        <ul className="space-y-0.5">
          {excludedCurrencies.map((ex) => (
            <li key={ex.currency} className="text-muted-foreground">
              <span className="font-mono">{ex.currency}</span> not converted (no rate available)
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
