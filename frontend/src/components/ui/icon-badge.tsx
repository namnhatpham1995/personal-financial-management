import { cn } from "@/lib/utils";

interface IconBadgeProps {
  children: React.ReactNode;
  className?: string;
}

export function IconBadge({ children, className }: IconBadgeProps) {
  return (
    <div
      className={cn(
        "flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-lg",
        "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400 border border-emerald-500/20",
        className
      )}
    >
      {children}
    </div>
  );
}
