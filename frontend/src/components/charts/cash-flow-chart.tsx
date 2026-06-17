"use client";

import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer,
} from "recharts";
import { IncomeExpenseTrend } from "@/services/analytics-service";
import { formatCurrency } from "@/lib/utils";

interface Props {
  data: IncomeExpenseTrend[];
  currency: string;
}

export function CashFlowChart({ data, currency }: Props) {
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
        <CartesianGrid strokeDasharray="3 3" stroke="hsl(var(--border))" />
        <XAxis dataKey="name" tick={{ fontSize: 11 }} />
        <YAxis tick={{ fontSize: 11 }} tickFormatter={axisLabel} />
        <Tooltip formatter={(v: number) => fmt(v)} />
        <Legend />
        <Bar dataKey="Income" fill="#10b981" radius={[4, 4, 0, 0]} />
        <Bar dataKey="Expense" fill="#ef4444" radius={[4, 4, 0, 0]} />
      </BarChart>
    </ResponsiveContainer>
  );
}
