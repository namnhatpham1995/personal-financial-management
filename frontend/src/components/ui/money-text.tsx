import { cn } from "@/lib/utils";

type TransactionType = "INCOME" | "EXPENSE" | "TRANSFER";

interface MoneyTextProps {
  amount: number;
  /** Controls sign prefix and semantic color */
  type?: TransactionType;
  /** Override color/sign logic; use for raw balances without a type */
  signed?: boolean;
  className?: string;
}

const typeStyles: Record<TransactionType, string> = {
  INCOME: "text-emerald-400",
  EXPENSE: "text-rose-400",
  TRANSFER: "text-slate-400",
};

const typeSign: Record<TransactionType, string> = {
  INCOME: "+",
  EXPENSE: "−",
  TRANSFER: "",
};

export function MoneyText({ amount, type, signed = false, className }: MoneyTextProps) {
  const colorClass = type ? typeStyles[type] : signed && amount >= 0 ? "text-emerald-400" : signed ? "text-rose-400" : "text-slate-100";
  const sign = type ? typeSign[type] : "";

  return (
    <span className={cn("font-mono tabular-nums", colorClass, className)}>
      {sign}{Math.abs(amount).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
    </span>
  );
}
