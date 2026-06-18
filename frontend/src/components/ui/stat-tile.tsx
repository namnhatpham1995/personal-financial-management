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
      <IconBadge>{icon}</IconBadge>
      <div>
        <p className="text-xs font-medium text-slate-400 tracking-wide uppercase">{title}</p>
        <p className={cn("mt-1 font-mono tabular-nums text-xl font-bold text-slate-100 tracking-tight", valueClassName)}>
          {value}
        </p>
      </div>
    </Card>
  );
}
