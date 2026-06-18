"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState, type ReactNode } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { toast } from "sonner";
import { Plus } from "lucide-react";
import { isAxiosError } from "axios";
import {
  categoryService,
  type Category,
  type CreateCategoryPayload,
} from "@/services/category-service";
import { CategoryRow, type CategoryRowProps } from "./category-row";
import { CategoryTypeGroup } from "./category-type-group";

const createSchema = z.object({
  name: z.string().min(1, "Name is required").max(100),
  transactionType: z.enum(["INCOME", "EXPENSE"]),
});

type CreateValues = z.infer<typeof createSchema>;

const inputCls =
  "rounded-lg border border-slate-800/60 bg-slate-900/60 px-3 py-2 text-sm text-slate-200 placeholder:text-slate-600 focus:outline-none focus:ring-2 focus:ring-emerald-500/40 focus:border-emerald-500/40 transition-colors";

export default function CategoriesPage() {
  const qc = useQueryClient();
  const [showForm, setShowForm] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [confirmDeleteId, setConfirmDeleteId] = useState<number | null>(null);

  const { data: categories = [], isLoading: catsLoading } = useQuery({
    queryKey: ["categories"],
    queryFn: categoryService.list,
  });

  const userCategories = categories.filter((c) => !c.system);
  const systemCategories = categories.filter((c) => c.system);

  const invalidateCategories = () => {
    qc.invalidateQueries({ queryKey: ["categories"] });
  };

  const createMutation = useMutation({
    mutationFn: (data: CreateCategoryPayload) => categoryService.create(data),
    onSuccess: () => { invalidateCategories(); setShowForm(false); toast.success("Category created"); },
    onError: (err) => {
      if (isAxiosError(err) && err.response?.status === 409) {
        toast.error("A category with that name already exists for this type");
      } else {
        toast.error("Failed to create category");
      }
    },
  });

  const renameMutation = useMutation({
    mutationFn: ({ id, name }: { id: number; name: string }) => categoryService.update(id, { name }),
    onSuccess: () => { invalidateCategories(); setEditingId(null); toast.success("Category renamed"); },
    onError: (err) => {
      if (isAxiosError(err) && err.response?.status === 409) toast.error("A category with that name already exists for this type");
      else if (isAxiosError(err) && err.response?.status === 403) toast.error("System categories cannot be modified");
      else toast.error("Failed to rename category");
    },
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => categoryService.delete(id),
    onSuccess: () => { invalidateCategories(); setConfirmDeleteId(null); toast.success("Category deleted - usages reassigned to Uncategorized"); },
    onError: (err) => {
      if (isAxiosError(err) && err.response?.status === 403) toast.error("System categories cannot be deleted");
      else toast.error("Failed to delete category");
    },
  });

  const rowProps = (category: Category, readonly: boolean): CategoryRowProps => ({
    category,
    isEditing: editingId === category.id,
    isConfirmingDelete: confirmDeleteId === category.id,
    onEditStart: () => setEditingId(category.id),
    onEditCancel: () => setEditingId(null),
    onRename: (name) => renameMutation.mutate({ id: category.id, name }),
    onDeleteRequest: () => setConfirmDeleteId(category.id),
    onDeleteCancel: () => setConfirmDeleteId(null),
    onDeleteConfirm: () => deleteMutation.mutate(category.id),
    isRenamePending: renameMutation.isPending,
    isDeletePending: deleteMutation.isPending && confirmDeleteId === category.id,
    readonly,
  });

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold tracking-tight text-slate-100">Categories</h1>
        <button
          onClick={() => setShowForm(!showForm)}
          className="flex items-center gap-2 rounded-lg bg-emerald-500/10 border border-emerald-500/20 px-4 py-2 text-sm font-medium text-emerald-400 hover:bg-emerald-500/20 transition-colors"
        >
          <Plus className="h-4 w-4" /> New Category
        </button>
      </div>

      {showForm && (
        <CreateForm
          onSubmit={(values) => createMutation.mutate(values)}
          onCancel={() => setShowForm(false)}
          isPending={createMutation.isPending}
        />
      )}

      {catsLoading ? (
        <p className="text-slate-500">Loading...</p>
      ) : (
        <div className="space-y-6">
          {userCategories.length > 0 && (
            <Section title="My Categories">
              <CategoryTypeGroups categories={userCategories} rowProps={(c) => rowProps(c, false)} />
            </Section>
          )}

          {systemCategories.length > 0 && (
            <Section title="Default Categories" subtitle="Read-only — available to all users">
              <CategoryTypeGroups categories={systemCategories} rowProps={(c) => rowProps(c, true)} />
            </Section>
          )}

          {categories.length === 0 && (
            <p className="text-sm text-slate-500">No categories yet. Create one above.</p>
          )}
        </div>
      )}
    </div>
  );
}

function CategoryTypeGroups({
  categories,
  rowProps,
}: {
  categories: Category[];
  rowProps: (c: Category) => CategoryRowProps;
}) {
  const income = categories.filter((c) => c.transactionType === "INCOME");
  const expense = categories.filter((c) => c.transactionType === "EXPENSE");

  return (
    <>
      {income.length > 0 && (
        <CategoryTypeGroup type="INCOME" label="Income" count={income.length}>
          {income.map((c) => <CategoryRow key={c.id} {...rowProps(c)} />)}
        </CategoryTypeGroup>
      )}
      {expense.length > 0 && (
        <CategoryTypeGroup type="EXPENSE" label="Expense" count={expense.length}>
          {expense.map((c) => <CategoryRow key={c.id} {...rowProps(c)} />)}
        </CategoryTypeGroup>
      )}
    </>
  );
}

function Section({ title, subtitle, children }: { title: string; subtitle?: string; children: ReactNode }) {
  return (
    <div>
      <div className="mb-3">
        <h2 className="text-xs font-semibold uppercase tracking-wide text-slate-500">{title}</h2>
        {subtitle && <p className="text-xs text-slate-600">{subtitle}</p>}
      </div>
      <div className="divide-y divide-slate-800/40 rounded-xl border border-slate-800/60 bg-slate-900/40 backdrop-blur-sm">
        {children}
      </div>
    </div>
  );
}

function CreateForm({ onSubmit, onCancel, isPending }: { onSubmit: (v: CreateValues) => void; onCancel: () => void; isPending: boolean }) {
  const { register, handleSubmit, formState: { errors } } = useForm<CreateValues>({ resolver: zodResolver(createSchema) });

  return (
    <div className="rounded-xl border border-slate-800/60 bg-slate-900/40 backdrop-blur-sm p-5">
      <h2 className="mb-4 font-semibold tracking-tight text-slate-100">New Category</h2>
      <form onSubmit={handleSubmit(onSubmit)} className="flex flex-wrap items-end gap-4">
        <div className="min-w-40 flex-1">
          <label className="mb-1.5 block text-xs font-medium uppercase tracking-wide text-slate-500">Name</label>
          <input {...register("name")} placeholder="e.g. Gym & Fitness" className={`w-full ${inputCls}`} />
          {errors.name && <p className="mt-1 text-xs text-rose-400">{errors.name.message}</p>}
        </div>
        <div>
          <label className="mb-1.5 block text-xs font-medium uppercase tracking-wide text-slate-500">Type</label>
          <select {...register("transactionType")} className={inputCls}>
            <option value="EXPENSE">Expense</option>
            <option value="INCOME">Income</option>
          </select>
        </div>
        <div className="flex gap-2">
          <button type="submit" disabled={isPending}
            className="rounded-lg bg-emerald-500/10 border border-emerald-500/20 px-4 py-2 text-sm font-medium text-emerald-400 hover:bg-emerald-500/20 disabled:opacity-50 transition-colors">
            {isPending ? "Saving..." : "Save"}
          </button>
          <button type="button" onClick={onCancel}
            className="rounded-lg border border-slate-800/60 px-4 py-2 text-sm text-slate-400 hover:bg-slate-800/60 transition-colors">
            Cancel
          </button>
        </div>
      </form>
    </div>
  );
}
