"use client";

import { useQuery } from "@tanstack/react-query";
import { analyticsService, CurrencyNetWorth } from "@/services/analytics-service";
import { accountService } from "@/services/account-service";
import { formatCurrency } from "@/lib/utils";
import { TrendingUp, TrendingDown, Wallet, AlertCircle } from "lucide-react";

export default function DashboardPage() {
  const { data: netWorthByCurrency = [] } = useQuery({
    queryKey: ["netWorth"],
    queryFn: analyticsService.netWorth,
  });

  const { data: accounts = [] } = useQuery({
    queryKey: ["accounts"],
    queryFn: accountService.list,
  });

  const { data: budgetProgress = [] } = useQuery({
    queryKey: ["budgetProgress"],
    queryFn: analyticsService.budgetProgress,
  });

  const overBudget = budgetProgress.filter((b) => b.overBudget);
  const multiCurrency = netWorthByCurrency.length > 1;

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">Overview</h1>

      {/* Net worth — one section per currency */}
      {netWorthByCurrency.length === 0 ? (
        <p className="text-muted-foreground text-sm">No accounts yet. Create one to see your overview.</p>
      ) : (
        netWorthByCurrency.map((bucket) => (
          <CurrencyNetWorthSection key={bucket.currency} bucket={bucket} showCurrencyLabel={multiCurrency} />
        ))
      )}

      {/* Account balances */}
      <section>
        <h2 className="mb-3 text-lg font-semibold">Accounts</h2>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {accounts.map((acc) => (
            <div key={acc.id} className="rounded-xl border border-border bg-card p-4 shadow-sm">
              <p className="text-xs font-medium uppercase text-muted-foreground">{acc.accountType}</p>
              <p className="mt-1 font-semibold">{acc.name}</p>
              <p className="mt-2 text-xl font-bold">
                {formatCurrency(acc.currentBalance, acc.currency)}
              </p>
            </div>
          ))}
        </div>
      </section>

      {/* Over-budget alerts */}
      {overBudget.length > 0 && (
        <section>
          <h2 className="mb-3 text-lg font-semibold text-destructive">Budget Alerts</h2>
          <div className="space-y-2">
            {overBudget.map((b) => (
              <div
                key={b.budgetId}
                className="flex items-center gap-3 rounded-lg border border-destructive/30 bg-destructive/10 p-3 text-sm"
              >
                <AlertCircle className="h-4 w-4 text-destructive" />
                <span>
                  <strong>{b.budgetName}</strong> is over budget
                </span>
              </div>
            ))}
          </div>
        </section>
      )}
    </div>
  );
}

function CurrencyNetWorthSection({
  bucket,
  showCurrencyLabel,
}: {
  bucket: CurrencyNetWorth;
  showCurrencyLabel: boolean;
}) {
  return (
    <section>
      {showCurrencyLabel && (
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
