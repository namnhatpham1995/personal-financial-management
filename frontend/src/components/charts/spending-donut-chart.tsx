"use client";

import { PieChart, Pie, Cell, Tooltip, ResponsiveContainer } from "recharts";
import { SpendingByCategory } from "@/services/analytics-service";
import { formatCurrency } from "@/lib/utils";
import { useChartTheme } from "@/lib/use-chart-theme";

/** Optional distinctly-styled slice for money that flowed IN (e.g. incoming transfers). */
export interface IncomingSlice {
  amount: number;
  label?: string;
}

interface Props {
  data: SpendingByCategory[];
  currency: string;
  /** When present with a positive amount, renders a hatched "incoming" slice + legend row. */
  incoming?: IncomingSlice;
}

const INCOMING_HATCH_ID = "incoming-hatch";

export function SpendingDonutChart({ data, currency, incoming }: Props) {
  const theme = useChartTheme();

  const hasIncoming = incoming != null && incoming.amount > 0;

  if (data.length === 0 && !hasIncoming) {
    return (
      <p className="py-8 text-center text-sm text-muted-foreground">
        No expense data for this period.
      </p>
    );
  }

  const top7 = data.slice(0, 7);
  const expenseTotal = top7.reduce((s, d) => s + Number(d.total), 0);
  const fmt = (v: number) => formatCurrency(v, currency);

  // Expense slices are solid, color-coded; the incoming slice (if any) is appended
  // last with a hatched fill so it reads as received-not-spent.
  const incomingLabel = incoming?.label ?? "Incoming transfers";
  const chartData = [
    ...top7.map((d) => ({ name: d.categoryName, value: Number(d.total), incoming: false })),
    ...(hasIncoming ? [{ name: incomingLabel, value: incoming!.amount, incoming: true }] : []),
  ];

  const sliceFill = (i: number, isIncoming: boolean) =>
    isIncoming ? `url(#${INCOMING_HATCH_ID})` : theme.series[i % theme.series.length];

  return (
    <div>
      <ResponsiveContainer width="100%" height={200}>
        <PieChart>
          <defs>
            {/* Diagonal hatch used to distinguish the incoming slice from spending. */}
            <pattern
              id={INCOMING_HATCH_ID}
              patternUnits="userSpaceOnUse"
              width={6}
              height={6}
              patternTransform="rotate(45)"
            >
              <rect width={6} height={6} fill={theme.axis} fillOpacity={0.25} />
              <line x1={0} y1={0} x2={0} y2={6} stroke={theme.axis} strokeWidth={2} />
            </pattern>
          </defs>
          <Pie
            data={chartData}
            cx="50%"
            cy="50%"
            innerRadius={55}
            outerRadius={85}
            dataKey="value"
            strokeWidth={0}
          >
            {chartData.map((d, i) => (
              <Cell key={i} fill={sliceFill(i, d.incoming)} />
            ))}
          </Pie>
          <Tooltip
            formatter={(v: number) => fmt(v)}
            contentStyle={{
              background: theme.tooltipBg,
              border: `1px solid ${theme.tooltipBorder}`,
              borderRadius: "0.75rem",
              fontSize: 12,
              color: theme.tooltipColor,
            }}
          />
        </PieChart>
      </ResponsiveContainer>
      <ul className="mt-2 space-y-1">
        {chartData.map((d, i) => (
          <li key={d.name} className="flex items-center justify-between text-xs">
            <span className="flex items-center gap-1.5 text-foreground">
              <span
                className="h-2 w-2 flex-shrink-0 rounded-full"
                style={
                  d.incoming
                    ? {
                        backgroundImage: `repeating-linear-gradient(45deg, ${theme.axis}, ${theme.axis} 1px, transparent 1px, transparent 3px)`,
                        backgroundColor: "transparent",
                        border: `1px solid ${theme.axis}`,
                      }
                    : { backgroundColor: theme.series[i % theme.series.length] }
                }
              />
              {d.name}
            </span>
            <span className="font-mono tabular-nums text-muted-foreground">
              {/* Incoming shows its absolute amount; expense slices show share of expense. */}
              {d.incoming
                ? fmt(d.value)
                : expenseTotal > 0
                ? `${((d.value / expenseTotal) * 100).toFixed(0)}%`
                : "—"}
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}
