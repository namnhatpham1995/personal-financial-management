import { useState, type ReactNode } from "react";
import { ChevronDown } from "lucide-react";

interface CategoryTypeGroupProps {
  type: "INCOME" | "EXPENSE";
  label: string;
  count: number;
  children: ReactNode;
}

const typeStyles = {
  INCOME: {
    label: "text-emerald-600",
    chip: "bg-emerald-100 text-emerald-700",
  },
  EXPENSE: {
    label: "text-rose-600",
    chip: "bg-rose-100 text-rose-700",
  },
};

export function CategoryTypeGroup({ type, label, count, children }: CategoryTypeGroupProps) {
  const [open, setOpen] = useState(false);
  const groupId = `category-type-group-${type.toLowerCase()}`;
  const styles = typeStyles[type];

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
          <span className={styles.label}>{label}</span>
          <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${styles.chip}`}>
            {count}
          </span>
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
