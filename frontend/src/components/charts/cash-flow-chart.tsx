"use client";

import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer,
} from "recharts";
import { IncomeExpenseTrend } from "@/services/analytics-service";
import { formatCurrency } from "@/lib/utils";

/* Dark-theme palette shared across chart components */
export const CHART_THEME = {
  grid: "rgba(148,163,184,0.08)",       // slate-400/8
  axis: "#64748b",                       // slate-500
  tooltipBg: "rgba(15,23,42,0.95)",      // slate-950/95
  tooltipBorder: "rgba(148,163,184,0.15)",
  income: "#34d399",                     // emerald-400
  expense: "#fb7185",                    // rose-400
  series: ["#34d399","#818cf8","#fb7185","#fbbf24","#38bdf8","#a78bfa","#f97316"],
} as const;

interface Props {
  data: IncomeExpenseTrend[];
  currency: string;
}

export function CashFlowChart({ data, currency }: Props) {
  if (data.length === 0) {
    return (
      <p className="py-8 text-center text-sm text-slate-500">
        No transaction data for this period.
      </p>
    );
  }

  const chartData = data.map((t) => ({
    name: `${t.year}-${String(t.month).padStart(2, "0")}`,
    Income: Number(t.totalIncome),
    Expense: Number(t.totalExpense),
  }));

  const fmt = (v: number) => formatCurrency(v, currency);
  const axisLabel = (v: number) => (v >= 1000 ? `${(v / 1000).toFixed(0)}k` : String(v));

  return (
    <ResponsiveContainer width="100%" height={260}>
      <BarChart data={chartData} margin={{ top: 0, right: 8, left: 0, bottom: 0 }}>
        <CartesianGrid strokeDasharray="3 3" stroke={CHART_THEME.grid} vertical={false} />
        <XAxis
          dataKey="name"
          tick={{ fontSize: 11, fill: CHART_THEME.axis, fontFamily: "var(--font-mono)" }}
          axisLine={false}
          tickLine={false}
        />
        <YAxis
          tick={{ fontSize: 11, fill: CHART_THEME.axis }}
          tickFormatter={axisLabel}
          axisLine={false}
          tickLine={false}
        />
        <Tooltip
          formatter={(v: number) => fmt(v)}
          contentStyle={{
            background: CHART_THEME.tooltipBg,
            border: `1px solid ${CHART_THEME.tooltipBorder}`,
            borderRadius: "0.75rem",
            fontSize: 12,
            color: "#f1f5f9",
          }}
          cursor={{ fill: "rgba(148,163,184,0.05)" }}
        />
        <Legend
          wrapperStyle={{ fontSize: 12, color: CHART_THEME.axis }}
        />
        <Bar dataKey="Income" fill={CHART_THEME.income} radius={[4, 4, 0, 0]} />
        <Bar dataKey="Expense" fill={CHART_THEME.expense} radius={[4, 4, 0, 0]} />
      </BarChart>
    </ResponsiveContainer>
  );
}
