"use client";

import { PieChart, Pie, Cell, Tooltip, ResponsiveContainer } from "recharts";
import { SpendingByCategory } from "@/services/analytics-service";
import { formatCurrency } from "@/lib/utils";
import { CHART_THEME } from "./cash-flow-chart";

interface Props {
  data: SpendingByCategory[];
  currency: string;
}

export function SpendingDonutChart({ data, currency }: Props) {
  if (data.length === 0) {
    return (
      <p className="py-8 text-center text-sm text-slate-500">
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
            strokeWidth={0}
          >
            {chartData.map((_, i) => (
              <Cell key={i} fill={CHART_THEME.series[i % CHART_THEME.series.length]} />
            ))}
          </Pie>
          <Tooltip
            formatter={(v: number) => fmt(v)}
            contentStyle={{
              background: CHART_THEME.tooltipBg,
              border: `1px solid ${CHART_THEME.tooltipBorder}`,
              borderRadius: "0.75rem",
              fontSize: 12,
              color: "#f1f5f9",
            }}
          />
        </PieChart>
      </ResponsiveContainer>
      <ul className="mt-2 space-y-1">
        {chartData.map((d, i) => (
          <li key={d.name} className="flex items-center justify-between text-xs">
            <span className="flex items-center gap-1.5 text-slate-300">
              <span
                className="h-2 w-2 flex-shrink-0 rounded-full"
                style={{ backgroundColor: CHART_THEME.series[i % CHART_THEME.series.length] }}
              />
              {d.name}
            </span>
            <span className="font-mono tabular-nums text-slate-500">
              {total > 0 ? `${((d.value / total) * 100).toFixed(0)}%` : "—"}
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}
