"use client";

import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Pencil, Plus, Trash2 } from "lucide-react";
import { toast } from "sonner";
import {
  BudgetLimitForm,
  type BudgetLimitPayload,
} from "@/components/budget-limit-form";
import { analyticsService, type BudgetProgress } from "@/services/analytics-service";
import { budgetService, type Budget, type CreateBudgetPayload } from "@/services/budget-service";
import { categoryService } from "@/services/category-service";
import { BudgetProgressList } from "./budget-progress-list";

const inputCls =
  "w-full rounded-lg border border-border bg-card px-3 py-2 text-base text-foreground focus:outline-none focus:ring-2 focus:ring-primary/40 focus:border-primary/40 transition-colors";

export function BudgetProgressManager() {
  const queryClient = useQueryClient();
  const [editingBudgetId, setEditingBudgetId] = useState<number | null>(null);
  const [showAddForm, setShowAddForm] = useState(false);
  const [selectedCategoryId, setSelectedCategoryId] = useState<number | "">("");

  const { data: progress = [], isLoading: progressLoading } = useQuery({
    queryKey: ["budgetProgress"],
    queryFn: analyticsService.budgetProgress,
  });
  const { data: budgets = [], isLoading: budgetsLoading } = useQuery({
    queryKey: ["budgets"],
    queryFn: budgetService.list,
  });
  const { data: categories = [], isLoading: categoriesLoading } = useQuery({
    queryKey: ["categories"],
    queryFn: categoryService.list,
  });

  const budgetById = useMemo(() => {
    return new Map(budgets.map((budget) => [budget.id, budget]));
  }, [budgets]);

  const budgetCategoryIds = useMemo(() => {
    return new Set(budgets.map((budget) => budget.categoryId));
  }, [budgets]);

  const availableCategories = useMemo(() => {
    return categories.filter(
      (category) =>
        category.transactionType === "EXPENSE" && !budgetCategoryIds.has(category.id)
    );
  }, [budgetCategoryIds, categories]);

  const selectedCategory = availableCategories.find(
    (category) => category.id === selectedCategoryId
  );
  const isLoading = progressLoading || budgetsLoading || categoriesLoading;

  const invalidateBudgets = () => {
    queryClient.invalidateQueries({ queryKey: ["budgets"] });
    queryClient.invalidateQueries({ queryKey: ["budgetProgress"] });
  };

  const createMutation = useMutation({
    mutationFn: (data: CreateBudgetPayload) => budgetService.create(data),
    onSuccess: () => {
      invalidateBudgets();
      setShowAddForm(false);
      setSelectedCategoryId("");
      toast.success("Limit set");
    },
    onError: () => toast.error("Failed to set limit"),
  });

  const updateMutation = useMutation({
    mutationFn: ({ id, data }: { id: number; data: BudgetLimitPayload }) =>
      budgetService.update(id, data),
    onSuccess: () => {
      invalidateBudgets();
      setEditingBudgetId(null);
      toast.success("Limit updated");
    },
    onError: () => toast.error("Failed to update limit"),
  });

  const removeMutation = useMutation({
    mutationFn: (id: number) => budgetService.delete(id),
    onSuccess: () => {
      invalidateBudgets();
      setEditingBudgetId(null);
      toast.success("Limit removed");
    },
    onError: () => toast.error("Failed to remove limit"),
  });

  const pending =
    createMutation.isPending || updateMutation.isPending || removeMutation.isPending;

  const handleCreate = (data: BudgetLimitPayload) => {
    if (!selectedCategory) return;
    createMutation.mutate({ categoryId: selectedCategory.id, ...data });
  };

  const renderActions = (item: BudgetProgress) => {
    const budget = budgetById.get(item.budgetId);
    if (!budget) return null;

    return (
      <div className="flex items-center gap-1">
        <button
          type="button"
          onClick={() => setEditingBudgetId(budget.id)}
          className="rounded p-1 text-muted-foreground hover:bg-secondary hover:text-foreground transition-colors"
          aria-label={`Edit ${item.budgetName} limit`}
        >
          <Pencil className="h-3.5 w-3.5" />
        </button>
        <button
          type="button"
          onClick={() => removeMutation.mutate(budget.id)}
          disabled={pending}
          className="rounded p-1 text-muted-foreground hover:bg-rose-500/10 hover:text-rose-500 dark:hover:text-rose-400 disabled:opacity-50 transition-colors"
          aria-label={`Remove ${item.budgetName} limit`}
        >
          <Trash2 className="h-3.5 w-3.5" />
        </button>
      </div>
    );
  };

  const renderDetails = (item: BudgetProgress) => {
    const budget = budgetById.get(item.budgetId);
    if (!budget || editingBudgetId !== budget.id) return null;

    return (
      <BudgetLimitForm
        initialAmount={budget.amountLimit}
        initialPeriod={budget.period}
        onSubmit={(data) => updateMutation.mutate({ id: budget.id, data })}
        onCancel={() => setEditingBudgetId(null)}
        isPending={pending}
        className="mt-3"
      />
    );
  };

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-sm text-muted-foreground">
          {isLoading ? "Loading budget progress..." : `${progress.length} active limits`}
        </p>
        <button
          type="button"
          onClick={() => setShowAddForm((current) => !current)}
          disabled={isLoading}
          className="flex items-center gap-1.5 rounded-lg bg-emerald-500/10 border border-emerald-500/20 px-3 py-1.5 text-xs font-medium text-emerald-600 dark:text-emerald-400 hover:bg-emerald-500/20 disabled:opacity-50 transition-colors"
        >
          <Plus className="h-3.5 w-3.5" /> Add limit
        </button>
      </div>

      {showAddForm && (
        <div className="rounded-xl border border-border bg-card p-4">
          {availableCategories.length === 0 ? (
            <p className="text-sm text-muted-foreground">
              All expense categories already have limits.
            </p>
          ) : (
            <div className="space-y-3">
              <div>
                <label className="mb-1.5 block text-xs font-medium uppercase tracking-wide text-muted-foreground">
                  Expense category
                </label>
                <select
                  value={selectedCategoryId}
                  onChange={(event) =>
                    setSelectedCategoryId(
                      event.target.value === "" ? "" : Number(event.target.value)
                    )
                  }
                  className={inputCls}
                >
                  <option value="">Choose a category</option>
                  {availableCategories.map((category) => (
                    <option key={category.id} value={category.id}>
                      {category.name}
                    </option>
                  ))}
                </select>
              </div>

              {selectedCategory ? (
                <BudgetLimitForm
                  onSubmit={handleCreate}
                  onCancel={() => {
                    setShowAddForm(false);
                    setSelectedCategoryId("");
                  }}
                  isPending={pending}
                  submitLabel="Create"
                />
              ) : (
                <p className="text-xs text-muted-foreground/60">
                  Select an expense category to set its limit.
                </p>
              )}
            </div>
          )}
        </div>
      )}

      <BudgetProgressList
        budgets={progress}
        renderActions={renderActions}
        renderDetails={renderDetails}
      />
    </div>
  );
}
