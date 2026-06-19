import { cn } from "@/lib/utils";

type TransactionType = "INCOME" | "EXPENSE" | "TRANSFER";

interface MoneyTextProps {
  amount: number;
  type?: TransactionType;
  signed?: boolean;
  className?: string;
}

const typeStyles: Record<TransactionType, string> = {
  INCOME: "text-emerald-500 dark:text-emerald-400",
  EXPENSE: "text-rose-500 dark:text-rose-400",
  TRANSFER: "text-muted-foreground",
};

const typeSign: Record<TransactionType, string> = {
  INCOME: "+",
  EXPENSE: "−",
  TRANSFER: "",
};

export function MoneyText({ amount, type, signed = false, className }: MoneyTextProps) {
  const colorClass = type
    ? typeStyles[type]
    : signed && amount >= 0
    ? "text-emerald-500 dark:text-emerald-400"
    : signed
    ? "text-rose-500 dark:text-rose-400"
    : "text-foreground";
  const sign = type ? typeSign[type] : "";

  return (
    <span className={cn("font-mono tabular-nums", colorClass, className)}>
      {sign}{Math.abs(amount).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
    </span>
  );
}
