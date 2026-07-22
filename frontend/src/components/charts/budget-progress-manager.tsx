"use client";

import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Pencil, Plus, Trash2 } from "lucide-react";
import { toast } from "sonner";
import { useTranslations } from "next-intl";
import {
  BudgetLimitForm,
  type BudgetLimitPayload,
} from "@/components/budget-limit-form";
import { analyticsService, type BudgetProgress } from "@/services/analytics-service";
import { budgetService, type Budget, type CreateBudgetPayload } from "@/services/budget-service";
import { categoryService } from "@/services/category-service";
import { BudgetProgressList } from "./budget-progress-list";
import { Button } from "@/components/ui/button";
import { useIdempotencyKey } from "@/lib/use-idempotency-key";
import { getIdempotencyErrorCode } from "@/lib/idempotency-error";
import { useCategoryLabel } from "@/lib/category-label";

const inputCls =
  "w-full rounded-lg border border-border bg-card px-3 py-2 text-base text-foreground focus:outline-none focus:ring-2 focus:ring-primary/40 focus:border-primary/40 transition-colors";

interface BudgetProgressManagerProps {
  /** Budgets are scoped to this currency — new limits are created in it without a picker. */
  currency: string;
}

export function BudgetProgressManager({ currency }: BudgetProgressManagerProps) {
  const t = useTranslations("budgets.manager");
  const tCommon = useTranslations("common");
  const getCategoryLabel = useCategoryLabel();
  const queryClient = useQueryClient();
  const [editingBudgetId, setEditingBudgetId] = useState<number | null>(null);
  const [showAddForm, setShowAddForm] = useState(false);
  const [selectedCategoryId, setSelectedCategoryId] = useState<number | "">("");

  const { data: allProgress = [], isLoading: progressLoading } = useQuery({
    queryKey: ["budgetProgress"],
    queryFn: analyticsService.budgetProgress,
  });
  const { data: allBudgets = [], isLoading: budgetsLoading } = useQuery({
    queryKey: ["budgets"],
    queryFn: budgetService.list,
  });
  const { data: categories = [], isLoading: categoriesLoading } = useQuery({
    queryKey: ["categories"],
    queryFn: categoryService.list,
  });

  const progress = useMemo(
    () => allProgress.filter((item) => item.currency === currency),
    [allProgress, currency]
  );
  const budgets = useMemo(
    () => allBudgets.filter((budget) => budget.currency === currency),
    [allBudgets, currency]
  );

  const budgetById = useMemo(() => {
    return new Map(budgets.map((budget) => [budget.id, budget]));
  }, [budgets]);

  /** Expense categories that don't already have a budget in this currency. */
  const budgetedCategoryIds = useMemo(
    () => new Set(budgets.map((b) => b.categoryId)),
    [budgets]
  );

  const availableCategoriesToAdd = useMemo(() => {
    return categories.filter(
      (category) =>
        category.transactionType === "EXPENSE" &&
        !budgetedCategoryIds.has(category.id)
    );
  }, [budgetedCategoryIds, categories]);

  const selectedCategory = availableCategoriesToAdd.find(
    (category) => category.id === selectedCategoryId
  );
  const isLoading = progressLoading || budgetsLoading || categoriesLoading;

  const invalidateBudgets = () => {
    queryClient.invalidateQueries({ queryKey: ["budgets"] });
    queryClient.invalidateQueries({ queryKey: ["budgetProgress"] });
  };

  const createIdempotency = useIdempotencyKey(null);

  const createMutation = useMutation({
    mutationFn: (data: CreateBudgetPayload) =>
      budgetService.create(data, createIdempotency.resolve(data)),
    onSuccess: () => {
      createIdempotency.clear();
      invalidateBudgets();
      setShowAddForm(false);
      setSelectedCategoryId("");
      toast.success(t("toast.limitSet"));
    },
    onError: (err) => {
      const idempotencyCode = getIdempotencyErrorCode(err);
      if (idempotencyCode === "idempotency_key_conflict") {
        createIdempotency.clear();
        toast.error(t("toast.setFailed"));
      } else if (idempotencyCode === "operation_in_progress") {
        toast.error(tCommon("operationInProgress"));
      } else {
        toast.error(t("toast.setFailed"));
      }
    },
  });

  const updateMutation = useMutation({
    mutationFn: ({ id, data }: { id: number; data: BudgetLimitPayload }) =>
      budgetService.update(id, data),
    onSuccess: () => {
      invalidateBudgets();
      setEditingBudgetId(null);
      toast.success(t("toast.limitUpdated"));
    },
    onError: () => toast.error(t("toast.updateFailed")),
  });

  const removeMutation = useMutation({
    mutationFn: (id: number) => budgetService.delete(id),
    onSuccess: () => {
      invalidateBudgets();
      setEditingBudgetId(null);
      toast.success(t("toast.limitRemoved"));
    },
    onError: () => toast.error(t("toast.removeFailed")),
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
    const budgetLabel = getCategoryLabel({ name: item.budgetName, categoryId: item.categoryId }) ?? item.budgetName;

    return (
      <div className="flex items-center gap-1">
        <button
          type="button"
          onClick={() => setEditingBudgetId(budget.id)}
          className="inline-flex min-h-11 min-w-11 items-center justify-center rounded text-muted-foreground hover:bg-secondary hover:text-foreground transition-colors"
          aria-label={t("editAria", { budgetName: budgetLabel })}
        >
          <Pencil className="h-3.5 w-3.5" />
        </button>
        <button
          type="button"
          onClick={() => removeMutation.mutate(budget.id)}
          disabled={pending}
          className="inline-flex min-h-11 min-w-11 items-center justify-center rounded text-muted-foreground hover:bg-destructive/10 hover:text-destructive disabled:opacity-50 transition-colors"
          aria-label={t("removeAria", { budgetName: budgetLabel })}
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
        lockCurrency
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
          {isLoading ? t("loadingProgress") : t("activeLimits", { count: progress.length })}
        </p>
        <Button
          type="button"
          size="sm"
          onClick={() => setShowAddForm((current) => !current)}
          disabled={isLoading}
        >
          <Plus className="h-3.5 w-3.5" /> {t("addLimit")}
        </Button>
      </div>

      {showAddForm && (
        <div className="rounded-xl border border-border bg-card p-4">
          {availableCategoriesToAdd.length === 0 ? (
            <p className="text-sm text-muted-foreground">
              {t("allCurrencyLimitsSet", { currency })}
            </p>
          ) : (
            <div className="space-y-3">
              <div>
                <label className="mb-1.5 block text-xs font-medium uppercase tracking-wide text-muted-foreground">
                  {t("expenseCategory")}
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
                  <option value="">{t("chooseCategory")}</option>
                  {availableCategoriesToAdd.map((category) => (
                    <option key={category.id} value={category.id}>
                      {getCategoryLabel({ name: category.name, system: category.system })}
                    </option>
                  ))}
                </select>
              </div>

              {selectedCategory ? (
                <BudgetLimitForm
                  initialCurrency={currency}
                  lockCurrency
                  onSubmit={handleCreate}
                  onCancel={() => {
                    setShowAddForm(false);
                    setSelectedCategoryId("");
                  }}
                  isPending={pending}
                  submitLabel={t("create")}
                />
              ) : (
                <p className="text-xs text-muted-foreground/60">
                  {t("selectCategoryHint", { currency })}
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
