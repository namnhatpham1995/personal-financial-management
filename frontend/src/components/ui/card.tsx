import { cn } from "@/lib/utils";

interface CardProps extends React.HTMLAttributes<HTMLDivElement> {
  interactive?: boolean;
}

export function Card({ className, interactive = false, ...props }: CardProps) {
  return (
    <div
      className={cn(
        "rounded-lg border border-border bg-card shadow-card",
        interactive &&
          "cursor-pointer transition-all duration-200 hover:border-primary/30 hover:bg-hover-surface hover:shadow-card-hover",
        className
      )}
      {...props}
    />
  );
}
