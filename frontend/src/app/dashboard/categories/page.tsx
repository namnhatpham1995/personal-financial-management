"use client";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { categoryService, Category, CreateCategoryPayload } from "@/services/category-service";
import { budgetService, Budget, CreateBudgetPayload } from "@/services/budget-service";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { toast } from "sonner";
import { Plus } from "lucide-react";
import { isAxiosError } from "axios";
import { CategoryRow, LimitPayload } from "./category-row";

const createSchema = z.object({
  name: z.string().min(1, "Name is required").max(100),
  transactionType: z.enum(["INCOME", "EXPENSE"]),
});
type CreateValues = z.infer<typeof createSchema>;

// Build a budget map: categoryId → Budget (prefer MONTHLY when multiple budgets exist)
function buildBudgetMap(budgets: Budget[]): Map<number, Budget> {
  const map = new Map<number, Budget>();
  for (const b of budgets) {
    const existing = map.get(b.categoryId);
    if (!existing || b.period === "MONTHLY") map.set(b.categoryId, b);
  }
  return map;
}

export default function CategoriesPage() {
  const qc = useQueryClient();
  const [showForm, setShowForm] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [confirmDeleteId, setConfirmDeleteId] = useState<number | null>(null);

  const { data: categories = [], isLoading: catsLoading } = useQuery({
    queryKey: ["categories"],
    queryFn: categoryService.list,
  });

  const { data: budgets = [] } = useQuery({
    queryKey: ["budgets"],
    queryFn: budgetService.list,
  });

  const budgetMap = buildBudgetMap(budgets);
  const userCategories = categories.filter((c) => !c.system);
  const systemCategories = categories.filter((c) => c.system);

  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ["categories"] });
    qc.invalidateQueries({ queryKey: ["budgets"] });
  };

  const createMutation = useMutation({
    mutationFn: (data: CreateCategoryPayload) => categoryService.create(data),
    onSuccess: () => { invalidate(); setShowForm(false); toast.success("Category created"); },
    onError: (err) => {
      if (isAxiosError(err) && err.response?.status === 409)
        toast.error("A category with that name already exists for this type");
      else toast.error("Failed to create category");
    },
  });

  const renameMutation = useMutation({
    mutationFn: ({ id, name }: { id: number; name: string }) =>
      categoryService.update(id, { name }),
    onSuccess: () => { invalidate(); setEditingId(null); toast.success("Category renamed"); },
    onError: (err) => {
      if (isAxiosError(err) && err.response?.status === 409)
        toast.error("A category with that name already exists for this type");
      else if (isAxiosError(err) && err.response?.status === 403)
        toast.error("System categories cannot be modified");
      else toast.error("Failed to rename category");
    },
  });

  const typeMutation = useMutation({
    mutationFn: ({ id, transactionType }: { id: number; transactionType: "INCOME" | "EXPENSE" }) =>
      categoryService.update(id, { name: categories.find((c) => c.id === id)?.name ?? "", transactionType }),
    onSuccess: () => { invalidate(); toast.success("Category type updated"); },
    onError: (err) => {
      if (isAxiosError(err) && err.response?.status === 409)
        toast.error("A category with that name already exists for the new type");
      else toast.error("Failed to update type");
    },
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => categoryService.delete(id),
    onSuccess: () => {
      invalidate(); setConfirmDeleteId(null);
      toast.success("Category deleted — transactions moved to Uncategorized");
    },
    onError: (err) => {
      if (isAxiosError(err) && err.response?.status === 403)
        toast.error("System categories cannot be deleted");
      else toast.error("Failed to delete category");
    },
  });

  const createLimitMutation = useMutation({
    mutationFn: (data: CreateBudgetPayload) => budgetService.create(data),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["budgets"] }); toast.success("Limit set"); },
    onError: () => toast.error("Failed to set limit"),
  });

  const updateLimitMutation = useMutation({
    mutationFn: ({ id, data }: { id: number; data: Partial<CreateBudgetPayload> }) =>
      budgetService.update(id, data),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["budgets"] }); toast.success("Limit updated"); },
    onError: () => toast.error("Failed to update limit"),
  });

  const removeLimitMutation = useMutation({
    mutationFn: (id: number) => budgetService.delete(id),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["budgets"] }); toast.success("Limit removed"); },
    onError: () => toast.error("Failed to remove limit"),
  });

  const rowProps = (cat: Category, readonly: boolean) => ({
    category: cat,
    budget: budgetMap.get(cat.id),
    isEditing: editingId === cat.id,
    isConfirmingDelete: confirmDeleteId === cat.id,
    onEditStart: () => setEditingId(cat.id),
    onEditCancel: () => setEditingId(null),
    onRename: (name: string) => renameMutation.mutate({ id: cat.id, name }),
    onTypeChange: readonly ? undefined : (type: "INCOME" | "EXPENSE") =>
      typeMutation.mutate({ id: cat.id, transactionType: type }),
    onDeleteRequest: () => setConfirmDeleteId(cat.id),
    onDeleteCancel: () => setConfirmDeleteId(null),
    onDeleteConfirm: () => deleteMutation.mutate(cat.id),
    onSetLimit: (data: LimitPayload) => createLimitMutation.mutate(data),
    onUpdateLimit: (budgetId: number, data: Omit<LimitPayload, "categoryId">) =>
      updateLimitMutation.mutate({ id: budgetId, data }),
    onRemoveLimit: (budgetId: number) => removeLimitMutation.mutate(budgetId),
    isRenamePending: renameMutation.isPending,
    isDeletePending: deleteMutation.isPending && confirmDeleteId === cat.id,
    isLimitPending: createLimitMutation.isPending || updateLimitMutation.isPending || removeLimitMutation.isPending,
    readonly,
  });

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">Categories &amp; Limit</h1>
        <button
          onClick={() => setShowForm(!showForm)}
          className="flex items-center gap-2 rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90"
        >
          <Plus className="h-4 w-4" /> New Category
        </button>
      </div>

      {showForm && (
        <CreateForm
          onSubmit={(v) => createMutation.mutate(v)}
          onCancel={() => setShowForm(false)}
          isPending={createMutation.isPending}
        />
      )}

      {catsLoading ? (
        <p className="text-muted-foreground">Loading…</p>
      ) : (
        <div className="space-y-6">
          {userCategories.length > 0 && (
            <Section title="My Categories">
              {userCategories.map((cat) => (
                <CategoryRow key={cat.id} {...rowProps(cat, false)} />
              ))}
            </Section>
          )}
          {systemCategories.length > 0 && (
            <Section title="Default Categories" subtitle="Read-only — available to all users">
              {systemCategories.map((cat) => (
                <CategoryRow key={cat.id} {...rowProps(cat, true)} />
              ))}
            </Section>
          )}
          {categories.length === 0 && (
            <p className="text-muted-foreground">No categories yet. Create one above.</p>
          )}
        </div>
      )}
    </div>
  );
}

function Section({ title, subtitle, children }: { title: string; subtitle?: string; children: React.ReactNode }) {
  return (
    <div>
      <div className="mb-3">
        <h2 className="text-sm font-semibold uppercase tracking-wide text-muted-foreground">{title}</h2>
        {subtitle && <p className="text-xs text-muted-foreground">{subtitle}</p>}
      </div>
      <div className="divide-y divide-border rounded-xl border border-border bg-card">{children}</div>
    </div>
  );
}

function CreateForm({ onSubmit, onCancel, isPending }: { onSubmit: (v: CreateValues) => void; onCancel: () => void; isPending: boolean }) {
  const { register, handleSubmit, formState: { errors } } = useForm<CreateValues>({ resolver: zodResolver(createSchema) });
  return (
    <div className="rounded-xl border border-border bg-card p-5">
      <h2 className="mb-4 font-semibold">New Category</h2>
      <form onSubmit={handleSubmit(onSubmit)} className="flex flex-wrap items-end gap-4">
        <div className="flex-1 min-w-40">
          <label className="mb-1 block text-sm font-medium">Name</label>
          <input {...register("name")} placeholder="e.g. Gym & Fitness"
            className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring" />
          {errors.name && <p className="mt-1 text-xs text-destructive">{errors.name.message}</p>}
        </div>
        <div>
          <label className="mb-1 block text-sm font-medium">Type</label>
          <select {...register("transactionType")}
            className="rounded-md border border-input bg-background px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring">
            <option value="EXPENSE">Expense</option>
            <option value="INCOME">Income</option>
          </select>
        </div>
        <div className="flex gap-2">
          <button type="submit" disabled={isPending}
            className="rounded-md bg-primary px-4 py-2 text-sm text-primary-foreground hover:bg-primary/90 disabled:opacity-50">
            {isPending ? "Saving…" : "Save"}
          </button>
          <button type="button" onClick={onCancel}
            className="rounded-md border px-4 py-2 text-sm hover:bg-accent">Cancel</button>
        </div>
      </form>
    </div>
  );
}
