import { useState, type ReactNode } from "react";
import { ChevronDown } from "lucide-react";

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
        className="flex w-full items-center justify-between px-4 py-3 text-sm font-medium hover:bg-accent/50 transition-colors"
      >
        <div className="flex items-center gap-2">
          <span>{label}</span>
          <span className="rounded-full bg-muted px-2 py-0.5 text-xs text-muted-foreground">
            {count}
          </span>
        </div>
        <ChevronDown
          className={`h-4 w-4 text-muted-foreground transition-transform duration-200 ${open ? "rotate-180" : ""}`}
        />
      </button>

      {open && (
        <div id={groupId} className="divide-y divide-border">
          {children}
        </div>
      )}
    </div>
  );
}
