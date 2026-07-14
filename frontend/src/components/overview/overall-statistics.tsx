"use client";

import { AlertTriangle } from "lucide-react";
import { useTranslations } from "next-intl";
import type { ConvertedOverview } from "@/services/analytics-service";
import {
  convertedSpendingToSpendingByCategory,
  convertedTrendToIncomeExpenseTrend,
} from "@/lib/converted-overview-adapters";
import { CashFlowChart } from "@/components/charts/cash-flow-chart";
import { SpendingDonutChart } from "@/components/charts/spending-donut-chart";
import { RatesUsedNote } from "@/components/charts/rates-used-note";
import { Card } from "@/components/ui/card";

interface OverallStatisticsProps {
  mainCurrency: string;
  overview: ConvertedOverview | null;
  isLoading: boolean;
}

/**
 * Tier 1: overall cash flow + spending converted into the user's main currency.
 * Only rendered for multi-currency users — single-currency users already see
 * their one currency's native charts inline.
 */
export function OverallStatistics({ mainCurrency, overview, isLoading }: OverallStatisticsProps) {
  const t = useTranslations("overview");

  if (isLoading || !overview) return null;

  if (overview.ratesUnavailable) {
    return (
      <Card className="flex items-center gap-2 p-5 text-sm text-muted-foreground">
        <AlertTriangle className="h-4 w-4 flex-shrink-0 text-warning" aria-hidden="true" />
        {t("ratesUnavailableNotice")}
      </Card>
    );
  }

  const trend = convertedTrendToIncomeExpenseTrend(overview.trend, mainCurrency);
  const spending = convertedSpendingToSpendingByCategory(overview.spending, mainCurrency);

  return (
    <section className="space-y-4">
      <h2 className="text-lg font-semibold tracking-tight text-foreground">{t("overall")}</h2>
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <Card className="p-5">
          <h3 className="mb-4 font-semibold tracking-tight text-foreground">{t("cashFlow")}</h3>
          <CashFlowChart data={trend} currency={mainCurrency} orientation="horizontal" />
        </Card>
        <Card className="p-5">
          <h3 className="mb-4 font-semibold tracking-tight text-foreground">
            {t("spendingByCategory")}
          </h3>
          <SpendingDonutChart data={spending} currency={mainCurrency} />
        </Card>
      </div>
      <RatesUsedNote
        rates={overview.rates}
        asOf={overview.asOf}
        stale={overview.stale}
        excludedCurrencies={overview.excludedCurrencies}
      />
    </section>
  );
}
