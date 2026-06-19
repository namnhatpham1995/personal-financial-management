"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { format, subMonths, startOfMonth, endOfMonth } from "date-fns";
import {
  analyticsService,
  CurrencyNetWorth,
  IncomeExpenseTrend,
  SpendingByCategory,
} from "@/services/analytics-service";
import { accountService } from "@/services/account-service";
import { formatCurrency } from "@/lib/utils";
import { TrendingUp, TrendingDown, Wallet } from "lucide-react";
import { CashFlowChart } from "@/components/charts/cash-flow-chart";
import { SpendingDonutChart } from "@/components/charts/spending-donut-chart";
import { BudgetProgressManager } from "@/components/charts/budget-progress-manager";
import { StatTile } from "@/components/ui/stat-tile";
import { Card } from "@/components/ui/card";

const RANGE_OPTIONS = [
  { label: "1M", months: 1 },
  { label: "3M", months: 3 },
  { label: "6M", months: 6 },
  { label: "1Y", months: 12 },
] as const;

export default function DashboardPage() {
  const [months, setMonths] = useState(6);

  const from = format(startOfMonth(subMonths(new Date(), months - 1)), "yyyy-MM-dd");
  const to = format(endOfMonth(new Date()), "yyyy-MM-dd");

  const { data: netWorthByCurrency = [] } = useQuery({
    queryKey: ["netWorth"],
    queryFn: analyticsService.netWorth,
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

  const multiCurrency = netWorthByCurrency.length > 1;
  const currencies = Array.from(
    new Set([...trend.map((t) => t.currency), ...spending.map((s) => s.currency)])
  ).sort();

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold tracking-tight text-foreground">Overview</h1>
        <div className="flex rounded-lg border border-border bg-card p-0.5">
          {RANGE_OPTIONS.map(({ label, months: m }) => (
            <button
              key={label}
              onClick={() => setMonths(m)}
              className={`rounded-md px-3 py-1 text-sm font-medium transition-colors ${
                months === m
                  ? "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400 border border-emerald-500/20"
                  : "text-muted-foreground hover:text-foreground"
              }`}
            >
              {label}
            </button>
          ))}
        </div>
      </div>

      {netWorthByCurrency.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          No accounts yet. Create one to see your overview.
        </p>
      ) : (
        netWorthByCurrency.map((bucket) => (
          <NetWorthCards key={bucket.currency} bucket={bucket} showLabel={multiCurrency} />
        ))
      )}

      {currencies.length === 0 ? (
        <p className="text-sm text-muted-foreground">No transaction data for this period.</p>
      ) : (
        currencies.map((currency) => (
          <ChartSection
            key={currency}
            currency={currency}
            trend={trend.filter((t) => t.currency === currency)}
            spending={spending.filter((s) => s.currency === currency)}
            showLabel={currencies.length > 1}
          />
        ))
      )}

      <Card className="p-5">
        <h2 className="mb-4 font-semibold tracking-tight text-foreground">Budget Progress</h2>
        <BudgetProgressManager />
      </Card>

      <section>
        <h2 className="mb-3 text-lg font-semibold tracking-tight text-foreground">Accounts</h2>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {accounts.map((acc) => (
            <Card key={acc.id} className="p-4">
              <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                {acc.accountType}
              </p>
              <p className="mt-1 font-semibold text-foreground">{acc.name}</p>
              <p className="mt-2 font-mono tabular-nums text-xl font-bold text-foreground">
                {formatCurrency(acc.currentBalance, acc.currency)}
              </p>
            </Card>
          ))}
        </div>
      </section>
    </div>
  );
}

function NetWorthCards({ bucket, showLabel }: { bucket: CurrencyNetWorth; showLabel: boolean }) {
  return (
    <section>
      {showLabel && (
        <h2 className="mb-3 text-sm font-semibold tracking-wide uppercase text-muted-foreground">
          {bucket.currency}
        </h2>
      )}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        <StatTile
          title="Net Worth"
          value={formatCurrency(bucket.netWorth, bucket.currency)}
          icon={<Wallet className="h-5 w-5" />}
        />
        <StatTile
          title="Total Assets"
          value={formatCurrency(bucket.totalAssets, bucket.currency)}
          icon={<TrendingUp className="h-5 w-5" />}
          valueClassName="text-emerald-600 dark:text-emerald-400"
        />
        <StatTile
          title="Total Liabilities"
          value={formatCurrency(bucket.totalLiabilities, bucket.currency)}
          icon={<TrendingDown className="h-5 w-5" />}
          valueClassName="text-rose-600 dark:text-rose-400"
        />
      </div>
    </section>
  );
}

function ChartSection({
  currency,
  trend,
  spending,
  showLabel,
}: {
  currency: string;
  trend: IncomeExpenseTrend[];
  spending: SpendingByCategory[];
  showLabel: boolean;
}) {
  return (
    <div className="space-y-4">
      {showLabel && (
        <h2 className="text-sm font-semibold tracking-wide uppercase text-muted-foreground">
          {currency}
        </h2>
      )}
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <Card className="p-5">
          <h2 className="mb-4 font-semibold tracking-tight text-foreground">Cash Flow</h2>
          <CashFlowChart data={trend} currency={currency} />
        </Card>
        <Card className="p-5">
          <h2 className="mb-4 font-semibold tracking-tight text-foreground">Spending by Category</h2>
          <SpendingDonutChart data={spending} currency={currency} />
        </Card>
      </div>
    </div>
  );
}
