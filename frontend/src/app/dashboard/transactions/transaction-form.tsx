"use client";

import { useEffect } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useTranslations } from "next-intl";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import type { Transaction } from "@/services/transaction-service";
import type { Account } from "@/services/account-service";
import type { Category } from "@/services/category-service";

const schema = z.object({
  accountId: z.coerce.number(),
  transactionType: z.enum(["INCOME", "EXPENSE", "TRANSFER"]),
  amount: z.string().min(1),
  transactionDate: z.string().min(1),
  note: z.string().optional(),
  transferAccountId: z.coerce.number().optional(),
  categoryId: z.preprocess(
    (v) => (v === "" || v === undefined || v === null ? undefined : Number(v)),
    z.number().positive().optional()
  ),
});

export type TransactionFormValues = z.infer<typeof schema>;

interface Props {
  editingTx: Transaction | null;
  accounts: Account[];
  categories: Category[];
  isPending: boolean;
  onCancel: () => void;
  onSubmit: (values: TransactionFormValues) => void;
}

const inputCls =
  "w-full rounded-md border border-border bg-card px-3.5 py-2.5 text-base text-foreground placeholder:text-muted-foreground/50 focus:outline-none focus:ring-2 focus:ring-primary/40 focus:border-primary/40 transition-colors";

export function TransactionForm({ editingTx, accounts, categories, isPending, onCancel, onSubmit }: Props) {
  const isEditing = editingTx !== null;
  const t = useTranslations("transactions.form");
  const tCommon = useTranslations("common");

  const { register, handleSubmit, watch, reset, setValue, formState: { errors } } = useForm<TransactionFormValues>({
    resolver: zodResolver(schema),
  });

  useEffect(() => {
    if (editingTx) {
      reset({
        accountId: editingTx.accountId,
        transactionType: editingTx.transactionType,
        amount: String(editingTx.amount ?? ""),
        transactionDate: editingTx.transactionDate,
        note: editingTx.note ?? "",
        transferAccountId: editingTx.transferAccountId,
        categoryId: editingTx.categoryId,
      });
    } else {
      reset({ transactionType: "INCOME" });
    }
  }, [editingTx, reset]);

  const txType = watch("transactionType");
  const categoryId = watch("categoryId");

  useEffect(() => {
    if (!editingTx && categoryId) {
      const stillValid = categories.some((c) => c.id === categoryId && c.transactionType === txType);
      if (!stillValid) setValue("categoryId", undefined);
    }
  }, [txType]); // eslint-disable-line react-hooks/exhaustive-deps

  const relevantCategories = txType
    ? categories.filter((c) => c.transactionType === txType)
    : categories;

  return (
    <div className="rounded-xl border border-border bg-card p-5">
      <h2 className="mb-4 font-semibold tracking-tight text-foreground">
        {isEditing ? t("editTitle") : t("newTitle")}
      </h2>
      <form onSubmit={handleSubmit(onSubmit)} className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <Field label={t("fields.account")} error={errors.accountId?.message}>
          <select {...register("accountId")} disabled={isEditing} className={cn(inputCls, isEditing && "cursor-not-allowed opacity-50")}>
            {accounts.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
          </select>
        </Field>
        <Field label={t("fields.type")} error={errors.transactionType?.message}>
          <select {...register("transactionType")} disabled={isEditing} className={cn(inputCls, isEditing && "cursor-not-allowed opacity-50")}>
            {["INCOME", "EXPENSE", "TRANSFER"].map((type) => <option key={type}>{type}</option>)}
          </select>
        </Field>
        <Field label={t("fields.amount")} error={errors.amount?.message}>
          <input type="number" step="0.01" {...register("amount")} className={inputCls} />
        </Field>
        <Field label={t("fields.date")} error={errors.transactionDate?.message}>
          <input type="date" {...register("transactionDate")} className={inputCls} />
        </Field>
        {txType === "TRANSFER" && (
          <Field label={t("fields.transferToAccount")} error={errors.transferAccountId?.message}>
            <select {...register("transferAccountId")} disabled={isEditing} className={cn(inputCls, isEditing && "cursor-not-allowed opacity-50")}>
              {accounts.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
            </select>
          </Field>
        )}
        <Field label={t("fields.category")} error={errors.categoryId?.message}>
          <select {...register("categoryId")} className={inputCls}>
            <option value="">{t("noCategory")}</option>
            {relevantCategories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </Field>
        <Field label={t("fields.note")} error={errors.note?.message}>
          <input {...register("note")} className={inputCls} />
        </Field>
        <div className="sm:col-span-2 flex gap-2">
          <Button type="submit" disabled={isPending}>
            {isEditing ? t("saveChanges") : tCommon("save")}
          </Button>
          <Button type="button" variant="secondary" onClick={onCancel}>
            {tCommon("cancel")}
          </Button>
        </div>
      </form>
    </div>
  );
}

function Field({ label, error, children }: { label: string; error?: string; children: React.ReactNode }) {
  return (
    <div>
      <label className="mb-1.5 block text-xs font-medium uppercase tracking-wide text-muted-foreground">{label}</label>
      {children}
      {error && <p className="mt-1 text-xs text-destructive">{error}</p>}
    </div>
  );
}
