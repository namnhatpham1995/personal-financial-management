"use client";

import { useEffect, useMemo, useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useLocale, useTranslations } from "next-intl";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { exchangeRateService } from "@/services/exchange-rate-service";
import type { Transaction } from "@/services/transaction-service";
import type { Account } from "@/services/account-service";
import type { Category } from "@/services/category-service";

function buildSchema(accounts: Account[]) {
  return z
    .object({
      accountId: z.coerce.number(),
      transactionType: z.enum(["INCOME", "EXPENSE", "TRANSFER"]),
      amount: z.string().min(1),
      transactionDate: z.string().min(1),
      note: z.string().optional(),
      transferAccountId: z.coerce.number().optional(),
      destinationAmount: z.string().optional(),
      categoryId: z.preprocess(
        (v) => (v === "" || v === undefined || v === null ? undefined : Number(v)),
        z.number().positive().optional()
      ),
    })
    .superRefine((data, ctx) => {
      if (data.transactionType !== "TRANSFER" || !data.transferAccountId) return;
      const source = accounts.find((a) => a.id === data.accountId);
      const dest = accounts.find((a) => a.id === data.transferAccountId);
      if (!source || !dest) return;
      const crossCurrency = source.currency !== dest.currency;
      const hasDestinationAmount = Boolean(data.destinationAmount && data.destinationAmount.trim() !== "");
      if (crossCurrency && !hasDestinationAmount) {
        ctx.addIssue({ code: z.ZodIssueCode.custom, path: ["destinationAmount"], message: "required" });
      }
      if (!crossCurrency && hasDestinationAmount) {
        ctx.addIssue({ code: z.ZodIssueCode.custom, path: ["destinationAmount"], message: "notAllowed" });
      }
    });
}

export type TransactionFormValues = z.infer<ReturnType<typeof buildSchema>>;

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
  const locale = useLocale();
  const [isConverting, setIsConverting] = useState(false);
  const [convertError, setConvertError] = useState(false);
  const [lastRate, setLastRate] = useState<{ rate: number; asOf: string | null } | null>(null);

  const schema = useMemo(() => buildSchema(accounts), [accounts]);

  const { register, handleSubmit, watch, reset, setValue, formState: { errors } } = useForm<TransactionFormValues>({
    resolver: zodResolver(schema),
  });

  useEffect(() => {
    if (editingTx) {
      reset({
        accountId: editingTx.accountId,
        transactionType: editingTx.transactionType,
        amount: String(editingTx.amount ?? ""),
        destinationAmount: editingTx.destinationAmount ? String(editingTx.destinationAmount) : undefined,
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
  const accountId = watch("accountId");
  const transferAccountId = watch("transferAccountId");
  const amount = watch("amount");
  const destinationAmount = watch("destinationAmount");
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

  // watch() returns the raw uncoerced field value (a string, from the <select> DOM element) —
  // zod's coerce.number() only applies at resolver/submit time, not to live watched values.
  const sourceAccount = accounts.find((a) => a.id === Number(accountId));
  const destAccount = accounts.find((a) => a.id === Number(transferAccountId));
  const crossCurrency =
    txType === "TRANSFER" && !!sourceAccount && !!destAccount && sourceAccount.currency !== destAccount.currency;

  useEffect(() => {
    if (!crossCurrency) {
      setValue("destinationAmount", undefined);
      setLastRate(null);
    }
  }, [crossCurrency, setValue]);

  const handleAutoConvert = async () => {
    if (!sourceAccount || !destAccount) return;
    const sourceFilled = Boolean(amount && amount.trim() !== "" && !Number.isNaN(Number(amount)));
    const destFilled = Boolean(destinationAmount && destinationAmount.trim() !== "" && !Number.isNaN(Number(destinationAmount)));
    if (!sourceFilled && !destFilled) return;

    setIsConverting(true);
    setConvertError(false);
    try {
      if (destFilled && !sourceFilled) {
        const result = await exchangeRateService.convert(destAccount.currency, sourceAccount.currency, destinationAmount!);
        // setValue stores whatever type is passed in react-hook-form's internal state (used by
        // the zod resolver on submit) — the DOM input's own string coercion doesn't cover this,
        // so a raw number here fails validation with "Expected string, received number" on save.
        setValue("amount", String(result.convertedAmount));
        setLastRate({ rate: result.rate, asOf: result.asOf });
      } else {
        // source-only, or both filled — source side wins
        const result = await exchangeRateService.convert(sourceAccount.currency, destAccount.currency, amount!);
        setValue("destinationAmount", String(result.convertedAmount));
        setLastRate({ rate: result.rate, asOf: result.asOf });
      }
    } catch {
      setConvertError(true);
    } finally {
      setIsConverting(false);
    }
  };

  const conversionLine = useMemo(() => {
    if (!crossCurrency || !sourceAccount || !destAccount) return null;
    const amountNum = Number(amount);
    const destNum = Number(destinationAmount);
    if (!amount || !destinationAmount || Number.isNaN(amountNum) || Number.isNaN(destNum) || amountNum <= 0) return null;
    const rate = destNum / amountNum;
    return {
      amountText: amountNum.toLocaleString(locale, { maximumFractionDigits: 4 }),
      destText: destNum.toLocaleString(locale, { maximumFractionDigits: 4 }),
      rateText: rate.toLocaleString(locale, { maximumFractionDigits: 6 }),
    };
  }, [crossCurrency, sourceAccount, destAccount, amount, destinationAmount, locale]);

  const destinationAmountErrorMessage =
    errors.destinationAmount?.message === "required"
      ? t("destinationAmountRequired")
      : errors.destinationAmount?.message === "notAllowed"
      ? t("destinationAmountNotAllowed")
      : errors.destinationAmount?.message;

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
        {crossCurrency && (
          <Field
            label={`${t("fields.destinationAmount")} (${destAccount!.currency})`}
            error={destinationAmountErrorMessage}
          >
            <div className="flex gap-2">
              {/* step="any": Auto Convert fills scale-4 precision (matching backend storage),
                  which a step="0.01" input would silently reject via native HTML5 validation. */}
              <input type="number" step="any" {...register("destinationAmount")} className={inputCls} />
              <Button type="button" variant="secondary" disabled={isConverting} onClick={handleAutoConvert}>
                {t("autoConvert")}
              </Button>
            </div>
            {convertError && <p className="mt-1 text-xs text-destructive">{t("autoConvertFailed")}</p>}
            {conversionLine && (
              <p className="mt-1.5 font-mono tabular-nums text-xs text-muted-foreground">
                {conversionLine.amountText} {sourceAccount!.currency} → {conversionLine.destText} {destAccount!.currency}
                {" @ "}
                {conversionLine.rateText}
                {lastRate?.asOf ? ` (${new Date(lastRate.asOf).toLocaleDateString(locale)})` : ""}
              </p>
            )}
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
