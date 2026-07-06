"use client";

import { useTheme } from "next-themes";

export function useChartTheme() {
  const { resolvedTheme } = useTheme();
  const dark = resolvedTheme === "dark";

  // Reserve Gold chart theme: axis/grid/tooltip are evergreen-tinted neutrals
  // (matching the interior token ramp), income/expense mirror the exact
  // globals.css values, and the categorical series stays clear of the gold
  // hue (H88) per the gold shell-only rule — charts are an interior surface.
  return {
    grid: dark ? "oklch(0.28 0.02 165 / 0.5)" : "oklch(0.80 0.008 165 / 0.5)",
    axis: dark ? "oklch(0.66 0.015 160)" : "oklch(0.46 0.02 165)",
    tooltipBg: dark ? "oklch(0.195 0.018 165 / 0.97)" : "oklch(1 0 0 / 0.97)",
    tooltipBorder: dark ? "oklch(0.28 0.02 165)" : "oklch(0.80 0.008 165)",
    tooltipColor: dark ? "oklch(0.95 0.004 160)" : "oklch(0.20 0.02 165)",
    income: dark ? "oklch(0.78 0.12 150)" : "oklch(0.46 0.14 152)",
    expense: dark ? "oklch(0.72 0.17 18)" : "oklch(0.58 0.20 18)",
    series: dark
      ? [
          "oklch(0.78 0.12 150)",
          "oklch(0.72 0.14 275)",
          "oklch(0.72 0.17 18)",
          "oklch(0.75 0.14 70)",
          "oklch(0.72 0.12 220)",
          "oklch(0.72 0.14 300)",
          "oklch(0.72 0.15 45)",
        ]
      : [
          "oklch(0.46 0.14 152)",
          "oklch(0.50 0.16 275)",
          "oklch(0.58 0.20 18)",
          "oklch(0.55 0.15 70)",
          "oklch(0.50 0.14 220)",
          "oklch(0.50 0.16 300)",
          "oklch(0.52 0.17 45)",
        ],
  } as const;
}
