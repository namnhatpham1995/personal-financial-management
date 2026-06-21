"use client";

import { useTheme } from "next-themes";

export function useChartTheme() {
  const { resolvedTheme } = useTheme();
  const dark = resolvedTheme === "dark";

  return {
    grid: dark ? "rgba(148,163,184,0.08)" : "rgba(100,116,139,0.12)",
    axis: dark ? "#64748b" : "#64748b",
    tooltipBg: dark ? "rgba(15,23,42,0.97)" : "rgba(255,255,255,0.97)",
    tooltipBorder: dark ? "rgba(148,163,184,0.15)" : "rgba(100,116,139,0.2)",
    tooltipColor: dark ? "#f1f5f9" : "#0f172a",
    income: dark ? "#34d399" : "#059669",
    expense: dark ? "#fb7185" : "#e11d48",
    series: dark
      ? ["#34d399", "#818cf8", "#fb7185", "#fbbf24", "#38bdf8", "#a78bfa", "#f97316"]
      : ["#059669", "#6366f1", "#e11d48", "#d97706", "#0284c7", "#7c3aed", "#ea580c"],
  } as const;
}
