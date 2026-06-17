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
import { BudgetProgressList } from "@/components/charts/budget-progress-list";

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
  const { data: budgets = [] } = useQuery({
    queryKey: ["budgetProgress"],
    queryFn: analyticsService.budgetProgress,
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
      {/* Header with time-range toggle — affects charts only */}
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">Overview</h1>
        <div className="flex rounded-lg border border-border bg-muted p-0.5">
          {RANGE_OPTIONS.map(({ label, months: m }) => (
            <button
              key={label}
              onClick={() => setMonths(m)}
              className={`rounded-md px-3 py-1 text-sm font-medium transition-colors ${
                months === m
                  ? "bg-background text-foreground shadow-sm"
                  : "text-muted-foreground hover:text-foreground"
              }`}
            >
              {label}
            </button>
          ))}
        </div>
      </div>

      {/* KPI cards — always current snapshot, unaffected by range */}
      {netWorthByCurrency.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          No accounts yet. Create one to see your overview.
        </p>
      ) : (
        netWorthByCurrency.map((bucket) => (
          <NetWorthCards key={bucket.currency} bucket={bucket} showLabel={multiCurrency} />
        ))
      )}

      {/* Charts — per currency, driven by selected range */}
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

      {/* Budget progress — all budgets with traffic-light coloring */}
      <section className="rounded-xl border border-border bg-card p-5">
        <h2 className="mb-4 font-semibold">Budget Progress</h2>
        <BudgetProgressList budgets={budgets} />
      </section>

      {/* Account cards */}
      <section>
        <h2 className="mb-3 text-lg font-semibold">Accounts</h2>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {accounts.map((acc) => (
            <div key={acc.id} className="rounded-xl border border-border bg-card p-4 shadow-sm">
              <p className="text-xs font-medium uppercase text-muted-foreground">
                {acc.accountType}
              </p>
              <p className="mt-1 font-semibold">{acc.name}</p>
              <p className="mt-2 text-xl font-bold">
                {formatCurrency(acc.currentBalance, acc.currency)}
              </p>
            </div>
          ))}
        </div>
      </section>
    </div>
  );
}

function NetWorthCards({
  bucket,
  showLabel,
}: {
  bucket: CurrencyNetWorth;
  showLabel: boolean;
}) {
  return (
    <section>
      {showLabel && (
        <h2 className="mb-3 text-base font-semibold text-muted-foreground">{bucket.currency}</h2>
      )}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        <StatCard
          title="Net Worth"
          value={formatCurrency(bucket.netWorth, bucket.currency)}
          icon={<Wallet className="h-5 w-5 text-primary" />}
        />
        <StatCard
          title="Total Assets"
          value={formatCurrency(bucket.totalAssets, bucket.currency)}
          icon={<TrendingUp className="h-5 w-5 text-green-500" />}
          positive
        />
        <StatCard
          title="Total Liabilities"
          value={formatCurrency(bucket.totalLiabilities, bucket.currency)}
          icon={<TrendingDown className="h-5 w-5 text-destructive" />}
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
        <h2 className="text-base font-semibold text-muted-foreground">{currency}</h2>
      )}
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <div className="rounded-xl border border-border bg-card p-5">
          <h2 className="mb-4 font-semibold">Cash Flow</h2>
          <CashFlowChart data={trend} currency={currency} />
        </div>
        <div className="rounded-xl border border-border bg-card p-5">
          <h2 className="mb-4 font-semibold">Spending by Category</h2>
          <SpendingDonutChart data={spending} currency={currency} />
        </div>
      </div>
    </div>
  );
}

function StatCard({
  title,
  value,
  icon,
  positive,
}: {
  title: string;
  value: string;
  icon: React.ReactNode;
  positive?: boolean;
}) {
  return (
    <div className="flex items-center gap-4 rounded-xl border border-border bg-card p-5 shadow-sm">
      <div className="rounded-lg bg-muted p-2">{icon}</div>
      <div>
        <p className="text-sm text-muted-foreground">{title}</p>
        <p className={`text-2xl font-bold ${positive ? "text-green-600" : ""}`}>{value}</p>
      </div>
    </div>
  );
}
