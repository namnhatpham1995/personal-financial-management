"use client";

import { useLocale } from "next-intl";
import { formatAmount, formatCurrency, formatDate, formatRate } from "@/lib/utils";

/**
 * Locale-bound versions of the formatters in lib/utils.ts, using the active
 * UI locale from next-intl. Adopt this in place of the raw formatAmount/
 * formatCurrency/formatDate/formatRate imports when a component's display
 * strings are being localized.
 */
export function useLocaleFormatters() {
  const locale = useLocale();
  return {
    formatAmount: (amount: number | string) => formatAmount(amount, locale),
    formatCurrency: (amount: number | string, currency: string) => formatCurrency(amount, currency, locale),
    formatDate: (date: string) => formatDate(date, locale),
    formatRate: (from: string, to: string, rate: number) => formatRate(from, to, rate, locale),
  };
}
