import { cn } from "@/lib/utils";

interface IconBadgeProps {
  children: React.ReactNode;
  className?: string;
}

/** Emerald rounded icon chip — the square icon container from the blueprint */
export function IconBadge({ children, className }: IconBadgeProps) {
  return (
    <div
      className={cn(
        "flex h-10 w-10 flex-shrink-0 items-center justify-center rounded-lg",
        "bg-emerald-500/10 text-emerald-400 border border-emerald-500/20",
        className
      )}
    >
      {children}
    </div>
  );
}
