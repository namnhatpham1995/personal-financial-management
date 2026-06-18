import { cn } from "@/lib/utils";

interface CardProps extends React.HTMLAttributes<HTMLDivElement> {
  /** Enable hover border+background lift effect */
  interactive?: boolean;
}

export function Card({ className, interactive = false, ...props }: CardProps) {
  return (
    <div
      className={cn(
        "rounded-xl border border-slate-800/60 bg-slate-900/40 backdrop-blur-sm",
        interactive &&
          "transition-all duration-200 hover:border-emerald-500/30 hover:bg-slate-900/80 cursor-pointer",
        className
      )}
      {...props}
    />
  );
}
