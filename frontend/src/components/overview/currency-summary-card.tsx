"use client";

import Link from "next/link";
import { AlertTriangle } from "lucide-react";
import { useLocale, useTranslations } from "next-intl";
import { Card } from "@/components/ui/card";
import { MoneyText } from "@/components/ui/money-text";
import { formatCurrency } from "@/lib/utils";

interface CurrencySummaryCardProps {
  currency: string;
  /** Native total balance; undefined when the user holds no accounts in this currency. */
  nativeTotal?: string;
  accountCount: number;
  /** Net cash flow (income − expense) for the selected range, in this currency. */
  netCashFlow: number;
  isOverBudget: boolean;
}

/** Compact native-amounts-only card for one currency on Overview; drills down to its detail page. */
export function CurrencySummaryCard({
  currency,
  nativeTotal,
  accountCount,
  netCashFlow,
  isOverBudget,
}: CurrencySummaryCardProps) {
  const t = useTranslations("overview");
  const locale = useLocale();

  return (
    <Link
      href={`/dashboard/currency/${currency}`}
      className="block rounded-lg focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/40"
    >
      <Card interactive className="space-y-3 p-5">
        <div className="flex items-center justify-between gap-2">
          <h3 className="font-semibold tracking-tight text-foreground">{currency}</h3>
          {isOverBudget && (
            <span className="inline-flex items-center gap-1 rounded-full bg-destructive/10 px-2 py-0.5 text-xs font-medium text-destructive">
              <AlertTriangle className="h-3.5 w-3.5" />
              {t("overBudget")}
            </span>
          )}
        </div>

        {nativeTotal !== undefined && (
          <p className="truncate font-mono text-2xl font-bold tabular-nums text-foreground">
            {formatCurrency(nativeTotal, currency, locale)}
          </p>
        )}

        <div className="flex items-center justify-between gap-2 text-sm text-muted-foreground">
          <span>{t("accountCount", { count: accountCount })}</span>
          <MoneyText amount={netCashFlow} signed currency={currency} className="text-sm" />
        </div>
      </Card>
    </Link>
  );
}
