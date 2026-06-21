import { cn } from "@/lib/utils";

interface CardProps extends React.HTMLAttributes<HTMLDivElement> {
  interactive?: boolean;
}

export function Card({ className, interactive = false, ...props }: CardProps) {
  return (
    <div
      className={cn(
        "rounded-xl border border-border bg-card",
        interactive &&
          "transition-all duration-200 hover:border-primary/30 hover:bg-hover-surface cursor-pointer",
        className
      )}
      {...props}
    />
  );
}
