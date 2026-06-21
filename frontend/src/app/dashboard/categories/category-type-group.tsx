import { useState, type ReactNode } from "react";
import { ChevronDown } from "lucide-react";
import { Badge } from "@/components/ui/badge";

interface CategoryTypeGroupProps {
  type: "INCOME" | "EXPENSE";
  label: string;
  count: number;
  children: ReactNode;
}

export function CategoryTypeGroup({ type, label, count, children }: CategoryTypeGroupProps) {
  const [open, setOpen] = useState(false);
  const groupId = `category-type-group-${type.toLowerCase()}`;

  return (
    <div>
      <button
        type="button"
        onClick={() => setOpen((prev) => !prev)}
        aria-expanded={open}
        aria-controls={groupId}
        className="flex w-full items-center justify-between px-4 py-3 text-sm font-medium hover:bg-hover-surface transition-colors"
      >
        <div className="flex items-center gap-2">
          <span className={type === "INCOME" ? "text-emerald-600 dark:text-emerald-400" : "text-rose-600 dark:text-rose-400"}>{label}</span>
          <Badge variant={type === "INCOME" ? "income" : "expense"}>{count}</Badge>
        </div>
        <ChevronDown
          className={`h-4 w-4 text-muted-foreground transition-transform duration-200 ${open ? "rotate-180" : ""}`}
        />
      </button>

      {open && (
        <div id={groupId} className="divide-y divide-border pl-4">
          {children}
        </div>
      )}
    </div>
  );
}
