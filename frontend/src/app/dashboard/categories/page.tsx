"use client";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  categoryService,
  Category,
  CreateCategoryPayload,
} from "@/services/category-service";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { toast } from "sonner";
import { Plus, Pencil, Trash2, Lock, Check, X } from "lucide-react";
import { isAxiosError } from "axios";

// ─── Schemas ─────────────────────────────────────────────────────────────────

const createSchema = z.object({
  name: z.string().min(1, "Name is required").max(100),
  transactionType: z.enum(["INCOME", "EXPENSE", "TRANSFER"]),
});

const renameSchema = z.object({
  name: z.string().min(1, "Name is required").max(100),
});

type CreateValues = z.infer<typeof createSchema>;
type RenameValues = z.infer<typeof renameSchema>;

// ─── Page ─────────────────────────────────────────────────────────────────────

export default function CategoriesPage() {
  const qc = useQueryClient();
  const [showForm, setShowForm] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [confirmDeleteId, setConfirmDeleteId] = useState<number | null>(null);

  const { data: categories = [], isLoading } = useQuery({
    queryKey: ["categories"],
    queryFn: categoryService.list,
  });

  const systemCategories = categories.filter((c) => c.system);
  const userCategories = categories.filter((c) => !c.system);

  // ─── Mutations ──────────────────────────────────────────────────────────

  const createMutation = useMutation({
    mutationFn: (data: CreateCategoryPayload) => categoryService.create(data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["categories"] });
      setShowForm(false);
      toast.success("Category created");
    },
    onError: (err) => {
      if (isAxiosError(err) && err.response?.status === 409) {
        toast.error("A category with that name already exists for this type");
      } else {
        toast.error("Failed to create category");
      }
    },
  });

  const renameMutation = useMutation({
    mutationFn: ({ id, name }: { id: number; name: string }) =>
      categoryService.update(id, { name }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["categories"] });
      setEditingId(null);
      toast.success("Category renamed");
    },
    onError: (err) => {
      if (isAxiosError(err) && err.response?.status === 409) {
        toast.error("A category with that name already exists for this type");
      } else if (isAxiosError(err) && err.response?.status === 403) {
        toast.error("System categories cannot be modified");
      } else {
        toast.error("Failed to rename category");
      }
    },
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => categoryService.delete(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["categories"] });
      setConfirmDeleteId(null);
      toast.success("Category deleted — usages reassigned to Uncategorized");
    },
    onError: (err) => {
      if (isAxiosError(err) && err.response?.status === 403) {
        toast.error("System categories cannot be deleted");
      } else {
        toast.error("Failed to delete category");
      }
    },
  });

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">Categories</h1>
        <button
          onClick={() => { setShowForm(!showForm); }}
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

      {isLoading ? (
        <p className="text-muted-foreground">Loading…</p>
      ) : (
        <div className="space-y-6">
          {userCategories.length > 0 && (
            <Section title="My Categories">
              {userCategories.map((cat) => (
                <CategoryRow
                  key={cat.id}
                  category={cat}
                  isEditing={editingId === cat.id}
                  isConfirmingDelete={confirmDeleteId === cat.id}
                  onEditStart={() => setEditingId(cat.id)}
                  onEditCancel={() => setEditingId(null)}
                  onRename={(name) => renameMutation.mutate({ id: cat.id, name })}
                  onDeleteRequest={() => setConfirmDeleteId(cat.id)}
                  onDeleteCancel={() => setConfirmDeleteId(null)}
                  onDeleteConfirm={() => deleteMutation.mutate(cat.id)}
                  isRenamePending={renameMutation.isPending}
                  isDeletePending={deleteMutation.isPending && confirmDeleteId === cat.id}
                />
              ))}
            </Section>
          )}

          {systemCategories.length > 0 && (
            <Section title="Default Categories" subtitle="Read-only — available to all users">
              {systemCategories.map((cat) => (
                <CategoryRow
                  key={cat.id}
                  category={cat}
                  isEditing={false}
                  isConfirmingDelete={false}
                  onEditStart={() => {}}
                  onEditCancel={() => {}}
                  onRename={() => {}}
                  onDeleteRequest={() => {}}
                  onDeleteCancel={() => {}}
                  onDeleteConfirm={() => {}}
                  isRenamePending={false}
                  isDeletePending={false}
                  readonly
                />
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

// ─── Sub-components ───────────────────────────────────────────────────────────

function Section({
  title,
  subtitle,
  children,
}: {
  title: string;
  subtitle?: string;
  children: React.ReactNode;
}) {
  return (
    <div>
      <div className="mb-3">
        <h2 className="text-sm font-semibold uppercase tracking-wide text-muted-foreground">
          {title}
        </h2>
        {subtitle && <p className="text-xs text-muted-foreground">{subtitle}</p>}
      </div>
      <div className="divide-y divide-border rounded-xl border border-border bg-card">
        {children}
      </div>
    </div>
  );
}

interface CategoryRowProps {
  category: Category;
  isEditing: boolean;
  isConfirmingDelete: boolean;
  onEditStart: () => void;
  onEditCancel: () => void;
  onRename: (name: string) => void;
  onDeleteRequest: () => void;
  onDeleteCancel: () => void;
  onDeleteConfirm: () => void;
  isRenamePending: boolean;
  isDeletePending: boolean;
  readonly?: boolean;
}

function CategoryRow({
  category,
  isEditing,
  isConfirmingDelete,
  onEditStart,
  onEditCancel,
  onRename,
  onDeleteRequest,
  onDeleteCancel,
  onDeleteConfirm,
  isRenamePending,
  isDeletePending,
  readonly = false,
}: CategoryRowProps) {
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<RenameValues>({
    resolver: zodResolver(renameSchema),
    defaultValues: { name: category.name },
  });

  const handleEditStart = () => {
    reset({ name: category.name });
    onEditStart();
  };

  const typeBadge =
    category.transactionType === "INCOME"
      ? "bg-emerald-100 text-emerald-700"
      : category.transactionType === "EXPENSE"
      ? "bg-rose-100 text-rose-700"
      : "bg-sky-100 text-sky-700";

  return (
    <div className="px-4 py-3">
      {isEditing ? (
        <form
          onSubmit={handleSubmit((v) => onRename(v.name))}
          className="flex items-center gap-2"
        >
          <input
            {...register("name")}
            autoFocus
            className="flex-1 rounded-md border border-input bg-background px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
          />
          {errors.name && (
            <span className="text-xs text-destructive">{errors.name.message}</span>
          )}
          <button
            type="submit"
            disabled={isRenamePending}
            className="rounded-md bg-primary px-3 py-1.5 text-xs text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
          >
            <Check className="h-3.5 w-3.5" />
          </button>
          <button
            type="button"
            onClick={() => { reset(); onEditCancel(); }}
            className="rounded-md border px-3 py-1.5 text-xs hover:bg-accent"
          >
            <X className="h-3.5 w-3.5" />
          </button>
        </form>
      ) : isConfirmingDelete ? (
        <div className="flex items-center justify-between gap-4">
          <p className="text-sm text-muted-foreground">
            Delete <span className="font-medium text-foreground">{category.name}</span>?
            Existing transactions, budgets, and recurring items will move to{" "}
            <span className="font-medium">Uncategorized</span>.
          </p>
          <div className="flex shrink-0 gap-2">
            <button
              onClick={onDeleteConfirm}
              disabled={isDeletePending}
              className="rounded-md bg-destructive px-3 py-1.5 text-xs text-destructive-foreground hover:bg-destructive/90 disabled:opacity-50"
            >
              {isDeletePending ? "Deleting…" : "Delete"}
            </button>
            <button
              onClick={onDeleteCancel}
              className="rounded-md border px-3 py-1.5 text-xs hover:bg-accent"
            >
              Cancel
            </button>
          </div>
        </div>
      ) : (
        <div className="flex items-center justify-between gap-3">
          <div className="flex items-center gap-3">
            <span className="font-medium">{category.name}</span>
            <span
              className={`rounded-full px-2 py-0.5 text-xs font-medium ${typeBadge}`}
            >
              {category.transactionType}
            </span>
          </div>
          {readonly ? (
            <Lock className="h-3.5 w-3.5 text-muted-foreground" aria-label="System category — read-only" />
          ) : (
            <div className="flex gap-1">
              <button
                onClick={handleEditStart}
                className="rounded p-1.5 text-muted-foreground hover:bg-accent hover:text-accent-foreground"
                aria-label="Rename"
              >
                <Pencil className="h-3.5 w-3.5" />
              </button>
              <button
                onClick={onDeleteRequest}
                className="rounded p-1.5 text-muted-foreground hover:bg-accent hover:text-destructive"
                aria-label="Delete"
              >
                <Trash2 className="h-3.5 w-3.5" />
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function CreateForm({
  onSubmit,
  onCancel,
  isPending,
}: {
  onSubmit: (v: CreateValues) => void;
  onCancel: () => void;
  isPending: boolean;
}) {
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<CreateValues>({ resolver: zodResolver(createSchema) });

  return (
    <div className="rounded-xl border border-border bg-card p-5">
      <h2 className="mb-4 font-semibold">New Category</h2>
      <form onSubmit={handleSubmit(onSubmit)} className="flex flex-wrap items-end gap-4">
        <div className="flex-1 min-w-40">
          <label className="mb-1 block text-sm font-medium">Name</label>
          <input
            {...register("name")}
            placeholder="e.g. Gym & Fitness"
            className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
          />
          {errors.name && (
            <p className="mt-1 text-xs text-destructive">{errors.name.message}</p>
          )}
        </div>
        <div>
          <label className="mb-1 block text-sm font-medium">Type</label>
          <select
            {...register("transactionType")}
            className="rounded-md border border-input bg-background px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
          >
            <option value="EXPENSE">Expense</option>
            <option value="INCOME">Income</option>
            <option value="TRANSFER">Transfer</option>
          </select>
        </div>
        <div className="flex gap-2">
          <button
            type="submit"
            disabled={isPending}
            className="rounded-md bg-primary px-4 py-2 text-sm text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
          >
            {isPending ? "Saving…" : "Save"}
          </button>
          <button
            type="button"
            onClick={onCancel}
            className="rounded-md border px-4 py-2 text-sm hover:bg-accent"
          >
            Cancel
          </button>
        </div>
      </form>
    </div>
  );
}
