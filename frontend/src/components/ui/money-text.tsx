"use client";

import { useLocale } from "next-intl";
import { cn } from "@/lib/utils";

type TransactionType = "INCOME" | "EXPENSE" | "TRANSFER";

interface MoneyTextProps {
  amount: number;
  type?: TransactionType;
  signed?: boolean;
  /** Optional currency code appended after the amount (e.g. "EUR"). */
  currency?: string;
  className?: string;
}

const typeStyles: Record<TransactionType, string> = {
  INCOME: "text-income",
  EXPENSE: "text-expense",
  TRANSFER: "text-muted-foreground",
};

const typeSign: Record<TransactionType, string> = {
  INCOME: "+",
  EXPENSE: "−",
  TRANSFER: "",
};

export function MoneyText({ amount, type, signed = false, currency, className }: MoneyTextProps) {
  const locale = useLocale();
  const colorClass = type
    ? typeStyles[type]
    : signed && amount >= 0
    ? "text-income"
    : signed
    ? "text-expense"
    : "text-foreground";
  const sign = type ? typeSign[type] : "";

  if (!Number.isFinite(amount)) {
    return <span className={cn("font-mono tabular-nums text-muted-foreground", className)}>—</span>;
  }

  return (
    <span className={cn("font-mono tabular-nums", colorClass, className)}>
      {sign}
      {Math.abs(amount).toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
      {currency ? ` ${currency}` : ""}
    </span>
  );
}
