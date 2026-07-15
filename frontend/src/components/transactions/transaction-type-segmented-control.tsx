"use client";

import { cn } from "@/lib/utils";

export type TransactionTypeValue = "INCOME" | "EXPENSE" | "TRANSFER";

const TYPES: TransactionTypeValue[] = ["INCOME", "EXPENSE", "TRANSFER"];

interface Props {
  value: TransactionTypeValue;
  onChange: (value: TransactionTypeValue) => void;
  disabled?: boolean;
  labels: Record<TransactionTypeValue, string>;
}

/**
 * Three-way pill toggle for the transaction type. Radio-group semantics so
 * screen readers announce it as a single control with three choices, and
 * arrow keys move between segments the way a native radio group would.
 */
export function TransactionTypeSegmentedControl({ value, onChange, disabled, labels }: Props) {
  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (disabled) return;
    const currentIndex = TYPES.indexOf(value);
    if (e.key === "ArrowRight" || e.key === "ArrowDown") {
      e.preventDefault();
      onChange(TYPES[(currentIndex + 1) % TYPES.length]);
    } else if (e.key === "ArrowLeft" || e.key === "ArrowUp") {
      e.preventDefault();
      onChange(TYPES[(currentIndex - 1 + TYPES.length) % TYPES.length]);
    }
  };

  return (
    <div
      role="radiogroup"
      onKeyDown={handleKeyDown}
      className={cn(
        "inline-flex w-full rounded-full border border-border bg-secondary p-1",
        disabled && "cursor-not-allowed opacity-50"
      )}
    >
      {TYPES.map((type) => {
        const selected = type === value;
        return (
          <button
            key={type}
            type="button"
            role="radio"
            aria-checked={selected}
            tabIndex={selected ? 0 : -1}
            disabled={disabled}
            onClick={() => !disabled && onChange(type)}
            className={cn(
              "flex-1 rounded-full px-3 py-2 text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/40",
              selected
                ? "bg-primary text-primary-foreground"
                : "text-muted-foreground hover:text-foreground",
              disabled && "pointer-events-none"
            )}
          >
            {labels[type]}
          </button>
        );
      })}
    </div>
  );
}
