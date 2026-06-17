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
