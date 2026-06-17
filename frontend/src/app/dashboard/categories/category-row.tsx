"use client";

import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { Check, X, Pencil, Trash2, Lock, Plus } from "lucide-react";
import { Category } from "@/services/category-service";
import { Budget } from "@/services/budget-service";
import { LimitBar } from "@/components/limit-bar";
import { formatCurrency } from "@/lib/utils";

const renameSchema = z.object({ name: z.string().min(1, "Name is required").max(100) });
type RenameValues = z.infer<typeof renameSchema>;

const limitSchema = z.object({
  amount: z.string().min(1, "Enter an amount"),
  period: z.enum(["MONTHLY", "YEARLY"]),
});
type LimitValues = z.infer<typeof limitSchema>;

function getPeriodStart(period: "MONTHLY" | "YEARLY"): string {
  const today = new Date();
  if (period === "MONTHLY") {
    return `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, "0")}-01`;
  }
  return `${today.getFullYear()}-01-01`;
}

export interface LimitPayload {
  categoryId: number;
  amountLimit: string;
  period: string;
  startDate: string;
}

export interface CategoryRowProps {
  category: Category;
  budget?: Budget;
  isEditing: boolean;
  isConfirmingDelete: boolean;
  onEditStart: () => void;
  onEditCancel: () => void;
  onRename: (name: string) => void;
  onTypeChange?: (type: "INCOME" | "EXPENSE") => void;
  onDeleteRequest: () => void;
  onDeleteCancel: () => void;
  onDeleteConfirm: () => void;
  onSetLimit: (data: LimitPayload) => void;
  onUpdateLimit: (budgetId: number, data: Omit<LimitPayload, "categoryId">) => void;
  onRemoveLimit: (budgetId: number) => void;
  isRenamePending: boolean;
  isDeletePending: boolean;
  isLimitPending: boolean;
  readonly?: boolean;
}

export function CategoryRow({
  category, budget, isEditing, isConfirmingDelete,
  onEditStart, onEditCancel, onRename, onTypeChange,
  onDeleteRequest, onDeleteCancel, onDeleteConfirm,
  onSetLimit, onUpdateLimit, onRemoveLimit,
  isRenamePending, isDeletePending, isLimitPending,
  readonly = false,
}: CategoryRowProps) {
  const [showLimitForm, setShowLimitForm] = useState(false);

  const renameForm = useForm<RenameValues>({
    resolver: zodResolver(renameSchema),
    defaultValues: { name: category.name },
  });

  const limitForm = useForm<LimitValues>({
    resolver: zodResolver(limitSchema),
    defaultValues: { amount: budget?.amountLimit ?? "", period: budget?.period ?? "MONTHLY" },
  });

  const isExpense = category.transactionType === "EXPENSE";

  const openLimitForm = () => {
    limitForm.reset({ amount: budget?.amountLimit ?? "", period: budget?.period ?? "MONTHLY" });
    setShowLimitForm(true);
  };

  const handleLimitSubmit = (v: LimitValues) => {
    const startDate = getPeriodStart(v.period);
    if (budget) {
      onUpdateLimit(budget.id, { amountLimit: v.amount, period: v.period, startDate });
    } else {
      onSetLimit({ categoryId: category.id, amountLimit: v.amount, period: v.period, startDate });
    }
    setShowLimitForm(false);
  };

  const typeBadgeCls = category.transactionType === "INCOME"
    ? "bg-emerald-100 text-emerald-700"
    : "bg-rose-100 text-rose-700";

  if (isEditing) {
    return (
      <div className="px-4 py-3">
        <form onSubmit={renameForm.handleSubmit((v) => onRename(v.name))} className="flex items-center gap-2">
          <input {...renameForm.register("name")} autoFocus
            className="flex-1 rounded-md border border-input bg-background px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-ring" />
          {renameForm.formState.errors.name && (
            <span className="text-xs text-destructive">{renameForm.formState.errors.name.message}</span>
          )}
          <button type="submit" disabled={isRenamePending}
            className="rounded-md bg-primary px-3 py-1.5 text-xs text-primary-foreground hover:bg-primary/90 disabled:opacity-50">
            <Check className="h-3.5 w-3.5" />
          </button>
          <button type="button" onClick={() => { renameForm.reset(); onEditCancel(); }}
            className="rounded-md border px-3 py-1.5 text-xs hover:bg-accent">
            <X className="h-3.5 w-3.5" />
          </button>
        </form>
      </div>
    );
  }

  if (isConfirmingDelete) {
    return (
      <div className="px-4 py-3">
        <div className="flex items-center justify-between gap-4">
          <p className="text-sm text-muted-foreground">
            Delete <span className="font-medium text-foreground">{category.name}</span>?
            {" "}Transactions, budgets, and recurring items will move to{" "}
            <span className="font-medium">Uncategorized</span>.
          </p>
          <div className="flex shrink-0 gap-2">
            <button onClick={onDeleteConfirm} disabled={isDeletePending}
              className="rounded-md bg-destructive px-3 py-1.5 text-xs text-destructive-foreground hover:bg-destructive/90 disabled:opacity-50">
              {isDeletePending ? "Deleting…" : "Delete"}
            </button>
            <button onClick={onDeleteCancel} className="rounded-md border px-3 py-1.5 text-xs hover:bg-accent">Cancel</button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="px-4 py-2.5 space-y-2">
      <div className="flex items-center justify-between gap-3">
        <div className="flex min-w-0 flex-1 items-center gap-2">
          <span className="font-medium truncate">{category.name}</span>
          {readonly ? (
            <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${typeBadgeCls}`}>
              {category.transactionType === "INCOME" ? "Income" : "Expense"}
            </span>
          ) : (
            <select value={category.transactionType}
              onChange={(e) => onTypeChange?.(e.target.value as "INCOME" | "EXPENSE")}
              className="rounded-md border border-input bg-background px-2 py-0.5 text-xs font-medium focus:outline-none focus:ring-1 focus:ring-ring">
              <option value="EXPENSE">Expense</option>
              <option value="INCOME">Income</option>
            </select>
          )}
        </div>

        <div className="flex shrink-0 items-center gap-2">
          {isExpense && !readonly && (
            budget ? (
              <div className="flex items-center gap-1 text-xs text-muted-foreground">
                <span>{formatCurrency(budget.spent)} / {formatCurrency(budget.amountLimit)}</span>
                <span>{budget.period === "MONTHLY" ? "mo" : "yr"}</span>
                <button onClick={openLimitForm} className="rounded p-1 hover:bg-accent hover:text-accent-foreground" aria-label="Edit limit">
                  <Pencil className="h-3 w-3" />
                </button>
                <button onClick={() => onRemoveLimit(budget.id)} disabled={isLimitPending}
                  className="rounded p-1 hover:bg-accent hover:text-destructive disabled:opacity-50" aria-label="Remove limit">
                  <X className="h-3 w-3" />
                </button>
              </div>
            ) : (
              <button onClick={openLimitForm}
                className="flex items-center gap-1 rounded-md border px-2 py-0.5 text-xs text-muted-foreground hover:bg-accent hover:text-accent-foreground">
                <Plus className="h-3 w-3" /> Set limit
              </button>
            )
          )}
          {readonly ? (
            <Lock className="h-3.5 w-3.5 text-muted-foreground" aria-label="Read-only" />
          ) : (
            <div className="flex gap-1">
              <button onClick={() => { renameForm.reset({ name: category.name }); onEditStart(); }}
                className="rounded p-1.5 text-muted-foreground hover:bg-accent hover:text-accent-foreground" aria-label="Rename">
                <Pencil className="h-3.5 w-3.5" />
              </button>
              <button onClick={onDeleteRequest}
                className="rounded p-1.5 text-muted-foreground hover:bg-accent hover:text-destructive" aria-label="Delete">
                <Trash2 className="h-3.5 w-3.5" />
              </button>
            </div>
          )}
        </div>
      </div>

      {isExpense && budget && !showLimitForm && (
        <LimitBar spent={budget.spent} limit={budget.amountLimit} overBudget={budget.overBudget} />
      )}

      {showLimitForm && (
        <form onSubmit={limitForm.handleSubmit(handleLimitSubmit)} className="flex flex-wrap items-end gap-2 pt-1">
          <div>
            <label className="mb-1 block text-xs font-medium">Amount</label>
            <input type="number" step="0.01" {...limitForm.register("amount")}
              className="w-28 rounded-md border border-input bg-background px-2 py-1 text-xs focus:outline-none focus:ring-2 focus:ring-ring" />
            {limitForm.formState.errors.amount && (
              <p className="mt-0.5 text-xs text-destructive">{limitForm.formState.errors.amount.message}</p>
            )}
          </div>
          <div>
            <label className="mb-1 block text-xs font-medium">Period</label>
            <select {...limitForm.register("period")}
              className="rounded-md border border-input bg-background px-2 py-1 text-xs focus:outline-none focus:ring-2 focus:ring-ring">
              <option value="MONTHLY">Monthly</option>
              <option value="YEARLY">Yearly</option>
            </select>
          </div>
          <div className="flex gap-1">
            <button type="submit" disabled={isLimitPending}
              className="rounded-md bg-primary px-3 py-1 text-xs text-primary-foreground hover:bg-primary/90 disabled:opacity-50">
              {isLimitPending ? "Saving…" : "Save"}
            </button>
            <button type="button" onClick={() => setShowLimitForm(false)}
              className="rounded-md border px-3 py-1 text-xs hover:bg-accent">Cancel</button>
          </div>
        </form>
      )}
    </div>
  );
}
