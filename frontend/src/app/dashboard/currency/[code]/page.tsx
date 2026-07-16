"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { format, subMonths, startOfMonth, endOfMonth } from "date-fns";
import { useQuery } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { ArrowLeft } from "lucide-react";
import { analyticsService } from "@/services/analytics-service";
import { accountService } from "@/services/account-service";
import { CurrencyDetailBody } from "@/components/overview/currency-detail-body";

const RANGE_OPTIONS = [
  { label: "1M", months: 1 },
  { label: "3M", months: 3 },
  { label: "6M", months: 6 },
  { label: "1Y", months: 12 },
] as const;

export default function CurrencyDetailPage({ params }: { params: { code: string } }) {
  const code = params.code.toUpperCase();
  const t = useTranslations("overview");
  const [months, setMonths] = useState(6);

  const from = format(startOfMonth(subMonths(new Date(), months - 1)), "yyyy-MM-dd");
  const to = format(endOfMonth(new Date()), "yyyy-MM-dd");

  const { data: balancesByCurrency = [] } = useQuery({
    queryKey: ["balances"],
    queryFn: analyticsService.balances,
  });
  const { data: accounts = [] } = useQuery({
    queryKey: ["accounts"],
    queryFn: accountService.list,
  });
  const { data: trend = [] } = useQuery({
    queryKey: ["trend", from, to],
    queryFn: () => analyticsService.incomeVsExpense(from, to),
  });
  const { data: spending = [] } = useQuery({
    queryKey: ["spending", from, to],
    queryFn: () => analyticsService.spendingByCategory(from, to),
  });
  const { data: budgetProgress = [] } = useQuery({
    queryKey: ["budgetProgress"],
    queryFn: analyticsService.budgetProgress,
  });

  const hasFootprint = useMemo(
    () =>
      balancesByCurrency.some((b) => b.currency === code) ||
      trend.some((item) => item.currency === code) ||
      spending.some((item) => item.currency === code) ||
      budgetProgress.some((item) => item.currency === code),
    [balancesByCurrency, trend, spending, budgetProgress, code]
  );

  const nativeTotal = balancesByCurrency.find((b) => b.currency === code)?.totalBalance;

  return (
    <div className="space-y-6">
      <Link
        href="/dashboard"
        className="inline-flex items-center gap-1.5 rounded-sm text-sm text-muted-foreground transition-colors hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/40"
      >
        <ArrowLeft className="h-4 w-4" /> {t("backToOverview")}
      </Link>

      {!hasFootprint ? (
        <div className="rounded-lg border border-border bg-card p-8 text-center">
          <p className="font-medium text-foreground">{t("noFootprintTitle", { code })}</p>
          <p className="mt-1 text-sm text-muted-foreground">{t("noFootprintBody")}</p>
        </div>
      ) : (
        <>
          <div className="flex flex-wrap items-center justify-end gap-3">
            <div className="flex rounded-full border border-border bg-card p-0.5">
              {RANGE_OPTIONS.map(({ label, months: m }) => (
                <button
                  key={label}
                  onClick={() => setMonths(m)}
                  className={`rounded-full px-3 py-1 text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/40 ${
                    months === m
                      ? "bg-primary/10 text-primary border border-primary/20"
                      : "text-muted-foreground hover:text-foreground"
                  }`}
                >
                  {label}
                </button>
              ))}
            </div>
          </div>

          <CurrencyDetailBody
            currency={code}
            nativeTotal={nativeTotal}
            trend={trend.filter((item) => item.currency === code)}
            spending={spending.filter((item) => item.currency === code)}
            accounts={accounts.filter((account) => account.currency === code)}
          />
        </>
      )}
    </div>
  );
}
