import type { Config } from "tailwindcss";

const config: Config = {
  darkMode: ["class"],
  content: [
    "./src/pages/**/*.{js,ts,jsx,tsx,mdx}",
    "./src/components/**/*.{js,ts,jsx,tsx,mdx}",
    "./src/app/**/*.{js,ts,jsx,tsx,mdx}",
  ],
  theme: {
    extend: {
      borderRadius: {
        lg: "var(--radius)",
        md: "calc(var(--radius) - 4px)",
        sm: "calc(var(--radius) - 8px)",
      },
      fontFamily: {
        display: ["var(--font-display)", "Newsreader", "Georgia", "serif"],
        mono: ["var(--font-mono)", "Spline Sans Mono", "JetBrains Mono", "Menlo", "monospace"],
      },
      colors: {
        background: "oklch(var(--background) / <alpha-value>)",
        foreground: "oklch(var(--foreground) / <alpha-value>)",
        "surface-raised": "oklch(var(--surface-raised) / <alpha-value>)",
        "surface-overlay": "oklch(var(--surface-overlay) / <alpha-value>)",
        "hover-surface": "oklch(var(--hover-surface) / <alpha-value>)",
        card: {
          DEFAULT: "oklch(var(--card) / <alpha-value>)",
          foreground: "oklch(var(--card-foreground) / <alpha-value>)",
        },
        primary: {
          DEFAULT: "oklch(var(--primary) / <alpha-value>)",
          foreground: "oklch(var(--primary-foreground) / <alpha-value>)",
        },
        secondary: {
          DEFAULT: "oklch(var(--secondary) / <alpha-value>)",
          foreground: "oklch(var(--secondary-foreground) / <alpha-value>)",
        },
        muted: {
          DEFAULT: "oklch(var(--muted) / <alpha-value>)",
          foreground: "oklch(var(--muted-foreground) / <alpha-value>)",
        },
        accent: {
          DEFAULT: "oklch(var(--accent) / <alpha-value>)",
          foreground: "oklch(var(--accent-foreground) / <alpha-value>)",
        },
        destructive: {
          DEFAULT: "oklch(var(--destructive) / <alpha-value>)",
          foreground: "oklch(var(--destructive-foreground) / <alpha-value>)",
        },
        success: {
          DEFAULT: "oklch(var(--success) / <alpha-value>)",
        },
        warning: {
          DEFAULT: "oklch(var(--warning) / <alpha-value>)",
        },
        border: "oklch(var(--border) / <alpha-value>)",
        input: "oklch(var(--input) / <alpha-value>)",
        ring: "oklch(var(--ring) / <alpha-value>)",

        /* Brand shell — landing hero + auth header ONLY, never inside daily screens */
        reserve: {
          DEFAULT: "oklch(var(--reserve) / <alpha-value>)",
          deep: "oklch(var(--reserve-deep) / <alpha-value>)",
        },
        gold: {
          DEFAULT: "oklch(var(--gold) / <alpha-value>)",
          deep: "oklch(var(--gold-deep) / <alpha-value>)",
        },
        ivory: "oklch(var(--ivory-on-green) / <alpha-value>)",

        /* Semantic financial state — text/badge only, never a surface fill */
        income: "oklch(var(--income) / <alpha-value>)",
        expense: "oklch(var(--expense) / <alpha-value>)",
        transfer: "oklch(var(--transfer) / <alpha-value>)",
      },
      boxShadow: {
        card: "0 1px 3px 0 oklch(var(--reserve) / 0.10), 0 1px 2px -1px oklch(var(--reserve) / 0.06)",
        "card-hover": "0 4px 6px -1px oklch(var(--reserve) / 0.14), 0 2px 4px -2px oklch(var(--reserve) / 0.08)",
      },
    },
  },
  plugins: [],
};
export default config;
