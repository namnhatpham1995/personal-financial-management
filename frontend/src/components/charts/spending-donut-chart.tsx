"use client";

import { PieChart, Pie, Cell, Tooltip, ResponsiveContainer } from "recharts";
import { SpendingByCategory } from "@/services/analytics-service";
import { formatCurrency } from "@/lib/utils";

const COLORS = ["#3b82f6", "#10b981", "#f59e0b", "#ef4444", "#8b5cf6", "#06b6d4", "#f97316"];

interface Props {
  data: SpendingByCategory[];
  currency: string;
}

export function SpendingDonutChart({ data, currency }: Props) {
  if (data.length === 0) {
    return (
      <p className="py-8 text-center text-sm text-muted-foreground">
        No expense data for this period.
      </p>
    );
  }

  const top7 = data.slice(0, 7);
  const total = top7.reduce((s, d) => s + Number(d.total), 0);
  const chartData = top7.map((d) => ({ name: d.categoryName, value: Number(d.total) }));
  const fmt = (v: number) => formatCurrency(v, currency);

  return (
    <div>
      <ResponsiveContainer width="100%" height={200}>
        <PieChart>
          <Pie
            data={chartData}
            cx="50%"
            cy="50%"
            innerRadius={55}
            outerRadius={85}
            dataKey="value"
          >
            {chartData.map((_, i) => (
              <Cell key={i} fill={COLORS[i % COLORS.length]} />
            ))}
          </Pie>
          <Tooltip formatter={(v: number) => fmt(v)} />
        </PieChart>
      </ResponsiveContainer>
      <ul className="mt-2 space-y-1">
        {chartData.map((d, i) => (
          <li key={d.name} className="flex items-center justify-between text-xs">
            <span className="flex items-center gap-1.5">
              <span
                className="h-2.5 w-2.5 flex-shrink-0 rounded-full"
                style={{ backgroundColor: COLORS[i % COLORS.length] }}
              />
              {d.name}
            </span>
            <span className="text-muted-foreground">
              {total > 0 ? `${((d.value / total) * 100).toFixed(0)}%` : "—"}
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}
