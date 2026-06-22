import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

/** Format a plain number with grouping — use when currency is unavailable (e.g. budget limits). */
export function formatAmount(amount: number | string): string {
  return new Intl.NumberFormat("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(
    Number(amount)
  );
}

export function formatCurrency(amount: number | string, currency: string): string {
  return (
    new Intl.NumberFormat("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(
      Number(amount)
    ) +
    " " +
    currency
  );
}

export function formatDate(date: string): string {
  return new Intl.DateTimeFormat("en-US", { dateStyle: "medium" }).format(new Date(date));
}

/**
 * Formats an exchange rate for display, preserving sub-cent precision.
 * Uses maximumSignificantDigits (not minimumFractionDigits) so that small rates
 * like 0.0000403 render as "0.00004030" rather than "0.00".
 *
 * Examples:
 *   formatRate("USD", "VND", 25000)      → "1 USD = 25,000 VND"
 *   formatRate("VND", "USD", 0.0000403)  → "1 VND = 0.00004030 USD"
 */
export function formatRate(from: string, to: string, rate: number): string {
  const formatted = new Intl.NumberFormat("en-US", {
    maximumSignificantDigits: 4,
  }).format(rate);
  return `1 ${from} = ${formatted} ${to}`;
}
