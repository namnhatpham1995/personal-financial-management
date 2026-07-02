import { cn } from "@/lib/utils";
import { Card } from "./card";
import { IconBadge } from "./icon-badge";

interface StatTileProps {
  title: string;
  value: string;
  icon: React.ReactNode;
  valueClassName?: string;
  className?: string;
}

export function StatTile({ title, value, icon, valueClassName, className }: StatTileProps) {
  return (
    <Card className={cn("flex items-center gap-4 p-5", className)}>
      <StatCell title={title} value={value} icon={icon} valueClassName={valueClassName} />
    </Card>
  );
}

// Bare tile content with no Card wrapper, for grouping several stats inside one
// shared box (e.g. a combined Net Worth / Assets / Liabilities summary).
export function StatCell({ title, value, icon, valueClassName, className }: StatTileProps) {
  return (
    <div className={cn("flex items-center gap-4 p-5", className)}>
      <IconBadge>{icon}</IconBadge>
      <div className="min-w-0">
        <p className="truncate text-xs font-medium text-muted-foreground tracking-wide uppercase">{title}</p>
        <p
          title={value}
          className={cn("mt-1 truncate font-mono tabular-nums text-xl font-bold text-foreground tracking-tight", valueClassName)}
        >
          {value}
        </p>
      </div>
    </div>
  );
}
