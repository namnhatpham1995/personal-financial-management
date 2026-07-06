import { cn } from "@/lib/utils";

type BadgeVariant = "income" | "expense" | "transfer" | "neutral" | "default";

interface BadgeProps {
  variant?: BadgeVariant;
  className?: string;
  children: React.ReactNode;
}

const variantStyles: Record<BadgeVariant, string> = {
  income: "bg-income/10 text-income border border-income/20",
  expense: "bg-expense/10 text-expense border border-expense/20",
  transfer: "bg-transfer/10 text-transfer border border-transfer/20",
  neutral: "bg-secondary text-muted-foreground border border-border",
  default: "bg-secondary text-muted-foreground border border-border",
};

export function Badge({ variant = "default", className, children }: BadgeProps) {
  return (
    <span
      className={cn(
        "inline-flex items-center rounded-full px-2 py-0.5 text-[10px] font-medium",
        variantStyles[variant],
        className
      )}
    >
      {children}
    </span>
  );
}
