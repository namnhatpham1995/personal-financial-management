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

const CURRENCY_FALLBACK = ["USD", "VND", "EUR"];

const inputCls =
  "w-full rounded-lg border border-border bg-card px-3 py-2 text-base text-foreground focus:outline-none focus:ring-2 focus:ring-primary/40 focus:border-primary/40 transition-colors";

export function BudgetProgressManager() {
  const queryClient = useQueryClient();
  const [editingBudgetId, setEditingBudgetId] = useState<number | null>(null);
  const [showAddForm, setShowAddForm] = useState(false);
  const [selectedCategoryId, setSelectedCategoryId] = useState<number | "">("");
  const [selectedCurrency, setSelectedCurrency] = useState<string>("");

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

  /** Distinct currencies from existing budgets + the account set. */
  const availableCurrencies = useMemo(() => {
    const fromBudgets = budgets.map((b) => b.currency);
    const unique = Array.from(new Set([...fromBudgets, ...CURRENCY_FALLBACK]));
    return unique;
  }, [budgets]);

  /** Default to the first available currency on first load. */
  const activeCurrency = selectedCurrency || availableCurrencies[0] || "USD";

  const budgetById = useMemo(() => {
    return new Map(budgets.map((budget) => [budget.id, budget]));
  }, [budgets]);

  /**
   * Composite key set: tracks (categoryId, currency) pairs that already have a budget.
   * A category is only excluded from the picker when a budget of the SELECTED currency
   * already exists for it — not any currency.
   */
  const budgetedPairsForActiveCurrency = useMemo(() => {
    return new Set(
      budgets
        .filter((b) => b.currency === activeCurrency)
        .map((b) => b.categoryId)
    );
  }, [budgets, activeCurrency]);

  const availableCategoriesToAdd = useMemo(() => {
    return categories.filter(
      (category) =>
        category.transactionType === "EXPENSE" &&
        !budgetedPairsForActiveCurrency.has(category.id)
    );
  }, [budgetedPairsForActiveCurrency, categories]);

  const selectedCategory = availableCategoriesToAdd.find(
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
        initialCurrency={budget.currency}
        availableCurrencies={availableCurrencies}
        onSubmit={(data) => updateMutation.mutate({ id: budget.id, data })}
        onCancel={() => setEditingBudgetId(null)}
        isPending={pending}
        className="mt-3"
      />
    );
  };

  /** Render the currency badge next to the budget name in each progress row. */
  const renderCurrencyBadge = (item: BudgetProgress) => (
    <span className="inline-flex items-center rounded border border-border bg-secondary px-1.5 py-0.5 text-xs font-mono text-muted-foreground">
      {item.currency}
    </span>
  );

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-sm text-muted-foreground">
          {isLoading ? "Loading budget progress..." : `${progress.length} active limits`}
        </p>
        <div className="flex items-center gap-2">
          {/* Currency filter for the Add form and the available-categories picker */}
          <select
            value={activeCurrency}
            onChange={(e) => {
              setSelectedCurrency(e.target.value);
              setSelectedCategoryId("");
            }}
            className="rounded-lg border border-border bg-card px-2 py-1.5 text-xs text-foreground focus:outline-none focus:ring-2 focus:ring-primary/40 transition-colors"
            aria-label="Select currency"
          >
            {availableCurrencies.map((c) => (
              <option key={c} value={c}>{c}</option>
            ))}
          </select>
          <button
            type="button"
            onClick={() => setShowAddForm((current) => !current)}
            disabled={isLoading}
            className="flex items-center gap-1.5 rounded-lg bg-emerald-500/10 border border-emerald-500/20 px-3 py-1.5 text-xs font-medium text-emerald-600 dark:text-emerald-400 hover:bg-emerald-500/20 disabled:opacity-50 transition-colors"
          >
            <Plus className="h-3.5 w-3.5" /> Add limit
          </button>
        </div>
      </div>

      {showAddForm && (
        <div className="rounded-xl border border-border bg-card p-4">
          {availableCategoriesToAdd.length === 0 ? (
            <p className="text-sm text-muted-foreground">
              All expense categories already have {activeCurrency} limits.
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
                  {availableCategoriesToAdd.map((category) => (
                    <option key={category.id} value={category.id}>
                      {category.name}
                    </option>
                  ))}
                </select>
              </div>

              {selectedCategory ? (
                <BudgetLimitForm
                  initialCurrency={activeCurrency}
                  availableCurrencies={availableCurrencies}
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
                  Select an expense category to set its {activeCurrency} limit.
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
        renderCurrencyBadge={renderCurrencyBadge}
      />
    </div>
  );
}
