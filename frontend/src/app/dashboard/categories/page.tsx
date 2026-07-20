"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useMemo, useState, type ReactNode } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { toast } from "sonner";
import { Plus } from "lucide-react";
import { isAxiosError } from "axios";
import { useTranslations } from "next-intl";
import {
  categoryService,
  type Category,
  type CreateCategoryPayload,
} from "@/services/category-service";
import { CategoryRow, type CategoryRowProps } from "./category-row";
import { CategoryTypeGroup } from "./category-type-group";
import { Button } from "@/components/ui/button";
import { useIdempotencyKey } from "@/lib/use-idempotency-key";
import { getIdempotencyErrorCode } from "@/lib/idempotency-error";

function createSchema(t: (key: string) => string) {
  return z.object({
    name: z.string().min(1, t("validation.nameRequired")).max(100),
    transactionType: z.enum(["INCOME", "EXPENSE"]),
  });
}

type CreateValues = z.infer<ReturnType<typeof createSchema>>;

const inputCls =
  "rounded-md border border-border bg-card px-3.5 py-2.5 text-base text-foreground placeholder:text-muted-foreground/50 focus:outline-none focus:ring-2 focus:ring-primary/40 focus:border-primary/40 transition-colors";

export default function CategoriesPage() {
  const qc = useQueryClient();
  const t = useTranslations("categories");
  const tCommon = useTranslations("common");
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

  const createIdempotency = useIdempotencyKey(null);

  const createMutation = useMutation({
    mutationFn: (data: CreateCategoryPayload) =>
      categoryService.create(data, createIdempotency.resolve(data)),
    onSuccess: () => {
      createIdempotency.clear();
      invalidateCategories();
      setShowForm(false);
      toast.success(t("toast.created"));
    },
    onError: (err) => {
      const idempotencyCode = getIdempotencyErrorCode(err);
      if (idempotencyCode === "idempotency_key_conflict") {
        createIdempotency.clear();
        toast.error(t("toast.createFailed"));
      } else if (idempotencyCode === "operation_in_progress") {
        toast.error(tCommon("operationInProgress"));
      } else if (isAxiosError(err) && err.response?.status === 409) {
        createIdempotency.clear();
        toast.error(t("toast.duplicateName"));
      } else {
        toast.error(t("toast.createFailed"));
      }
    },
  });

  const renameMutation = useMutation({
    mutationFn: ({ id, name }: { id: number; name: string }) => categoryService.update(id, { name }),
    onSuccess: () => { invalidateCategories(); setEditingId(null); toast.success(t("toast.renamed")); },
    onError: (err) => {
      if (isAxiosError(err) && err.response?.status === 409) toast.error(t("toast.duplicateName"));
      else if (isAxiosError(err) && err.response?.status === 403) toast.error(t("toast.systemNoModify"));
      else toast.error(t("toast.renameFailed"));
    },
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => categoryService.delete(id),
    onSuccess: () => { invalidateCategories(); setConfirmDeleteId(null); toast.success(t("toast.deleted")); },
    onError: (err) => {
      if (isAxiosError(err) && err.response?.status === 403) toast.error(t("toast.systemNoDelete"));
      else toast.error(t("toast.deleteFailed"));
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
        <h1 className="text-2xl font-bold tracking-tight text-foreground">{t("title")}</h1>
        <Button onClick={() => setShowForm(!showForm)}>
          <Plus className="h-4 w-4" /> {t("newCategory")}
        </Button>
      </div>

      {showForm && (
        <CreateForm
          onSubmit={(values) => createMutation.mutate(values)}
          onCancel={() => setShowForm(false)}
          isPending={createMutation.isPending}
        />
      )}

      {catsLoading ? (
        <p className="text-muted-foreground">{tCommon("loading")}</p>
      ) : (
        <div className="space-y-6">
          {userCategories.length > 0 && (
            <Section title={t("myCategories")}>
              <CategoryTypeGroups categories={userCategories} rowProps={(c) => rowProps(c, false)} />
            </Section>
          )}

          {systemCategories.length > 0 && (
            <Section title={t("defaultCategories")} subtitle={t("defaultCategoriesSubtitle")}>
              <CategoryTypeGroups categories={systemCategories} rowProps={(c) => rowProps(c, true)} />
            </Section>
          )}

          {categories.length === 0 && (
            <p className="text-sm text-muted-foreground">{t("emptyState")}</p>
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
  const t = useTranslations("categories");
  const income = categories.filter((c) => c.transactionType === "INCOME");
  const expense = categories.filter((c) => c.transactionType === "EXPENSE");

  return (
    <>
      {income.length > 0 && (
        <CategoryTypeGroup type="INCOME" label={t("income")} count={income.length}>
          {income.map((c) => <CategoryRow key={c.id} {...rowProps(c)} />)}
        </CategoryTypeGroup>
      )}
      {expense.length > 0 && (
        <CategoryTypeGroup type="EXPENSE" label={t("expense")} count={expense.length}>
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
        <h2 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">{title}</h2>
        {subtitle && <p className="text-xs text-muted-foreground/60">{subtitle}</p>}
      </div>
      <div className="divide-y divide-border rounded-lg border border-border bg-card">
        {children}
      </div>
    </div>
  );
}

function CreateForm({ onSubmit, onCancel, isPending }: { onSubmit: (v: CreateValues) => void; onCancel: () => void; isPending: boolean }) {
  const t = useTranslations("categories");
  const tCommon = useTranslations("common");
  const schema = useMemo(() => createSchema(t), [t]);
  const { register, handleSubmit, formState: { errors } } = useForm<CreateValues>({ resolver: zodResolver(schema) });

  return (
    <div className="rounded-lg border border-border bg-card p-5">
      <h2 className="mb-4 font-semibold tracking-tight text-foreground">{t("newCategory")}</h2>
      <form onSubmit={handleSubmit(onSubmit)} className="flex flex-wrap items-end gap-4">
        <div className="min-w-40 flex-1">
          <label className="mb-1.5 block text-xs font-medium uppercase tracking-wide text-muted-foreground">{t("form.fields.name")}</label>
          <input {...register("name")} placeholder={t("form.namePlaceholder")} className={`w-full ${inputCls}`} />
          {errors.name && <p className="mt-1 text-xs text-destructive">{errors.name.message}</p>}
        </div>
        <div>
          <label className="mb-1.5 block text-xs font-medium uppercase tracking-wide text-muted-foreground">{t("form.fields.type")}</label>
          <select {...register("transactionType")} className={inputCls}>
            <option value="EXPENSE">{t("expense")}</option>
            <option value="INCOME">{t("income")}</option>
          </select>
        </div>
        <div className="flex gap-2">
          <Button type="submit" disabled={isPending}>
            {isPending ? tCommon("saving") : tCommon("save")}
          </Button>
          <Button type="button" variant="secondary" onClick={onCancel}>
            {tCommon("cancel")}
          </Button>
        </div>
      </form>
    </div>
  );
}
