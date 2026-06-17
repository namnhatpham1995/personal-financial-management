"use client";

import { useQuery } from "@tanstack/react-query";
import { analyticsService, SpendingByCategory, IncomeExpenseTrend, CurrencyNetWorth } from "@/services/analytics-service";
import { formatCurrency } from "@/lib/utils";
import { useState } from "react";
import { format, subMonths, startOfMonth, endOfMonth } from "date-fns";
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer,
  PieChart, Pie, Cell,
} from "recharts";

const COLORS = ["#3b82f6", "#10b981", "#f59e0b", "#ef4444", "#8b5cf6", "#06b6d4", "#f97316"];

export default function AnalyticsPage() {
  const [months, setMonths] = useState(6);

  const from = format(startOfMonth(subMonths(new Date(), months - 1)), "yyyy-MM-dd");
  const to = format(endOfMonth(new Date()), "yyyy-MM-dd");

  const { data: trend = [] } = useQuery({
    queryKey: ["trend", from, to],
    queryFn: () => analyticsService.incomeVsExpense(from, to),
  });

  const { data: spending = [] } = useQuery({
    queryKey: ["spending", from, to],
    queryFn: () => analyticsService.spendingByCategory(from, to),
  });

  const { data: budgets = [] } = useQuery({
    queryKey: ["budgetProgress"],
    queryFn: analyticsService.budgetProgress,
  });

  const { data: netWorthByCurrency = [] } = useQuery({
    queryKey: ["netWorth"],
    queryFn: analyticsService.netWorth,
  });

  // Group per-currency
  const currencies = Array.from(new Set([
    ...trend.map((t) => t.currency),
    ...spending.map((s) => s.currency),
  ])).sort();

  return (
    <div className="space-y-8">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">Analytics</h1>
        <select
          value={months}
          onChange={(e) => setMonths(Number(e.target.value))}
          className="rounded-md border border-input bg-background px-3 py-2 text-sm"
        >
          <option value={3}>Last 3 months</option>
          <option value={6}>Last 6 months</option>
          <option value={12}>Last 12 months</option>
        </select>
      </div>

      {/* Net worth per currency */}
      {netWorthByCurrency.map((bucket) => (
        <NetWorthSection key={bucket.currency} bucket={bucket} showLabel={netWorthByCurrency.length > 1} />
      ))}

      {/* Per-currency trend + spending charts */}
      {currencies.length === 0 ? (
        <p className="text-sm text-muted-foreground">No transaction data for this period.</p>
      ) : (
        currencies.map((currency) => (
          <CurrencyAnalyticsSection
            key={currency}
            currency={currency}
            trend={trend.filter((t) => t.currency === currency)}
            spending={spending.filter((s) => s.currency === currency)}
            showLabel={currencies.length > 1}
          />
        ))
      )}

      {/* Budget progress */}
      <div className="rounded-xl border border-border bg-card p-5">
        <h2 className="mb-4 font-semibold">Budget Progress</h2>
        {budgets.length === 0 ? (
          <p className="text-sm text-muted-foreground">No budgets configured.</p>
        ) : (
          <div className="space-y-4">
            {budgets.map((b) => {
              const pct = Math.min(Number(b.percentUsed), 100);
              return (
                <div key={b.budgetId}>
                  <div className="mb-1 flex justify-between text-sm">
                    <span className="font-medium">{b.budgetName}</span>
                    <span className="text-muted-foreground">
                      {b.spent} / {b.limitAmount}
                    </span>
                  </div>
                  <div className="h-2 overflow-hidden rounded-full bg-muted">
                    <div
                      className={`h-full rounded-full ${b.overBudget ? "bg-destructive" : "bg-primary"}`}
                      style={{ width: `${pct}%` }}
                    />
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}

// ── Net worth summary section ─────────────────────────────────────────────────

function NetWorthSection({ bucket, showLabel }: { bucket: CurrencyNetWorth; showLabel: boolean }) {
  return (
    <div className="space-y-2">
      {showLabel && (
        <h2 className="text-base font-semibold text-muted-foreground">{bucket.currency} Net Worth</h2>
      )}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        {[
          { label: "Net Worth", value: bucket.netWorth },
          { label: "Total Assets", value: bucket.totalAssets },
          { label: "Total Liabilities", value: bucket.totalLiabilities },
        ].map((s) => (
          <div key={s.label} className="rounded-xl border border-border bg-card p-5 shadow-sm">
            <p className="text-sm text-muted-foreground">{s.label}</p>
            <p className="mt-1 text-2xl font-bold">{formatCurrency(s.value, bucket.currency)}</p>
          </div>
        ))}
      </div>
    </div>
  );
}

// ── Per-currency charts ───────────────────────────────────────────────────────

function CurrencyAnalyticsSection({
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
  const trendData = trend.map((t) => ({
    name: `${t.year}-${String(t.month).padStart(2, "0")}`,
    Income: Number(t.totalIncome),
    Expense: Number(t.totalExpense),
    Net: Number(t.net),
  }));

  const pieData = spending.slice(0, 7).map((s) => ({
    name: s.categoryName,
    value: Number(s.total),
  }));

  const fmt = (v: number) => formatCurrency(v, currency);
  const axisLabel = (v: number) =>
    v >= 1000 ? `${(v / 1000).toFixed(0)}k ${currency}` : `${v} ${currency}`;

  return (
    <div className="space-y-6">
      {showLabel && (
        <h2 className="text-base font-semibold text-muted-foreground">{currency}</h2>
      )}

      {/* Income vs Expense */}
      <div className="rounded-xl border border-border bg-card p-5">
        <h2 className="mb-4 font-semibold">
          Income vs Expense{showLabel ? ` (${currency})` : ""}
        </h2>
        {trendData.length === 0 ? (
          <p className="text-sm text-muted-foreground">No data for this period.</p>
        ) : (
          <ResponsiveContainer width="100%" height={280}>
            <BarChart data={trendData} margin={{ top: 0, right: 16, left: 0, bottom: 0 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="hsl(var(--border))" />
              <XAxis dataKey="name" tick={{ fontSize: 12 }} />
              <YAxis tick={{ fontSize: 12 }} tickFormatter={axisLabel} />
              <Tooltip formatter={(v: number) => fmt(v)} />
              <Legend />
              <Bar dataKey="Income" fill="#10b981" radius={[4, 4, 0, 0]} />
              <Bar dataKey="Expense" fill="#ef4444" radius={[4, 4, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        )}
      </div>

      {/* Spending by category */}
      <div className="rounded-xl border border-border bg-card p-5">
        <h2 className="mb-4 font-semibold">
          Spending by Category{showLabel ? ` (${currency})` : ""}
        </h2>
        {pieData.length === 0 ? (
          <p className="text-sm text-muted-foreground">No expense data for this period.</p>
        ) : (
          <ResponsiveContainer width="100%" height={240}>
            <PieChart>
              <Pie
                data={pieData}
                cx="50%"
                cy="50%"
                outerRadius={90}
                dataKey="value"
                label={({ name, percent }) => `${name} ${(percent * 100).toFixed(0)}%`}
                labelLine={false}
              >
                {pieData.map((_, i) => (
                  <Cell key={i} fill={COLORS[i % COLORS.length]} />
                ))}
              </Pie>
              <Tooltip formatter={(v: number) => fmt(v)} />
            </PieChart>
          </ResponsiveContainer>
        )}
      </div>
    </div>
  );
}
