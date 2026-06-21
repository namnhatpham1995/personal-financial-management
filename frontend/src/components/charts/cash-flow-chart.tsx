"use client";

import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer,
} from "recharts";
import { IncomeExpenseTrend } from "@/services/analytics-service";
import { formatCurrency } from "@/lib/utils";
import { useChartTheme } from "@/lib/use-chart-theme";

interface Props {
  data: IncomeExpenseTrend[];
  currency: string;
}

export function CashFlowChart({ data, currency }: Props) {
  const theme = useChartTheme();

  if (data.length === 0) {
    return (
      <p className="py-8 text-center text-sm text-muted-foreground">
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
        <CartesianGrid strokeDasharray="3 3" stroke={theme.grid} vertical={false} />
        <XAxis
          dataKey="name"
          tick={{ fontSize: 11, fill: theme.axis, fontFamily: "var(--font-mono)" }}
          axisLine={false}
          tickLine={false}
        />
        <YAxis
          tick={{ fontSize: 11, fill: theme.axis }}
          tickFormatter={axisLabel}
          axisLine={false}
          tickLine={false}
        />
        <Tooltip
          formatter={(v: number) => fmt(v)}
          contentStyle={{
            background: theme.tooltipBg,
            border: `1px solid ${theme.tooltipBorder}`,
            borderRadius: "0.75rem",
            fontSize: 12,
            color: theme.tooltipColor,
          }}
          cursor={{ fill: "rgba(148,163,184,0.05)" }}
        />
        <Legend wrapperStyle={{ fontSize: 12, color: theme.axis }} />
        <Bar dataKey="Income" fill={theme.income} radius={[4, 4, 0, 0]} />
        <Bar dataKey="Expense" fill={theme.expense} radius={[4, 4, 0, 0]} />
      </BarChart>
    </ResponsiveContainer>
  );
}
