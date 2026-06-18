import { cn } from "@/lib/utils";

type BadgeVariant = "income" | "expense" | "transfer" | "neutral" | "default";

interface BadgeProps {
  variant?: BadgeVariant;
  className?: string;
  children: React.ReactNode;
}

const variantStyles: Record<BadgeVariant, string> = {
  income: "bg-emerald-500/10 text-emerald-400 border border-emerald-500/20",
  expense: "bg-rose-500/10 text-rose-400 border border-rose-500/20",
  transfer: "bg-blue-500/10 text-blue-400 border border-blue-500/20",
  neutral: "bg-slate-800 text-slate-400 border border-slate-700/50",
  default: "bg-slate-800 text-slate-400 border border-slate-700/50",
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
