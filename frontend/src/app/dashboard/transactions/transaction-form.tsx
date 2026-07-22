"use client";

import { useEffect, useMemo } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useLocale, useTranslations } from "next-intl";
import { Button } from "@/components/ui/button";
import { Field, fieldInputCls, inputCls } from "@/components/transactions/transaction-field";
import { TransactionTypeSegmentedControl } from "@/components/transactions/transaction-type-segmented-control";
import { TransferDirectionFields } from "@/components/transactions/transfer-direction-fields";
import { useAutoConversion } from "@/components/transactions/use-auto-conversion";
import { useDefaultAccountSelection } from "@/components/transactions/use-default-account-selection";
import { useTransactionTypeLabel } from "@/lib/enum-labels";
import { buildSchema, todayIsoDate, type TransactionFormValues } from "./transaction-form-schema";
import type { Transaction } from "@/services/transaction-service";
import type { Account } from "@/services/account-service";
import type { Category } from "@/services/category-service";

export type { TransactionFormValues } from "./transaction-form-schema";

interface Props {
  editingTx: Transaction | null;
  accounts: Account[];
  categories: Category[];
  isPending: boolean;
  onCancel: () => void;
  onSubmit: (values: TransactionFormValues) => void;
}

export function TransactionForm({ editingTx, accounts, categories, isPending, onCancel, onSubmit }: Props) {
  const isEditing = editingTx !== null;
  const t = useTranslations("transactions.form");
  const tCommon = useTranslations("common");
  const locale = useLocale();

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
      reset({ transactionType: "INCOME", transactionDate: todayIsoDate() });
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

  useDefaultAccountSelection({ isEditing, txType, accountId, transferAccountId, accounts, setValue });
  const { isLoading: isRateLoading, error: rateError, overridden, markOverridden, revert, conversionLine } = useAutoConversion({
    sourceAccount,
    destAccount,
    amount,
    destinationAmount,
    crossCurrency,
    initialOverridden: isEditing,
    locale,
    setDestinationAmount: (value) => setValue("destinationAmount", value),
  });

  useEffect(() => {
    if (txType === "TRANSFER" && accountId && Number(accountId) === Number(transferAccountId)) setValue("transferAccountId", undefined);
  }, [txType, accountId, transferAccountId, setValue]);

  const destinationAmountErrorMessage =
    errors.destinationAmount?.message === "required"
      ? t("destinationAmountRequired")
      : errors.destinationAmount?.message === "notAllowed"
      ? t("destinationAmountNotAllowed")
      : errors.destinationAmount?.message;

  const transferAccountIdErrorMessage =
    errors.transferAccountId?.message === "sameAccount"
      ? t("sameAccountNotAllowed")
      : errors.transferAccountId?.message;

  const getTypeLabel = useTransactionTypeLabel();
  const typeLabels = {
    INCOME: getTypeLabel("INCOME"),
    EXPENSE: getTypeLabel("EXPENSE"),
    TRANSFER: getTypeLabel("TRANSFER"),
  } as const;

  return (
    <div className="rounded-xl border border-border bg-card p-5">
      <h2 className="mb-4 font-semibold tracking-tight text-foreground">
        {isEditing ? t("editTitle") : t("newTitle")}
      </h2>
      <form onSubmit={handleSubmit(onSubmit)} className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <div className="sm:col-span-2">
          <label className="mb-1.5 block text-xs font-medium uppercase tracking-wide text-muted-foreground">
            {t("fields.type")}
          </label>
          <TransactionTypeSegmentedControl
            value={txType ?? "INCOME"}
            onChange={(value) => setValue("transactionType", value)}
            disabled={isEditing}
            labels={typeLabels}
          />
        </div>

        {txType === "TRANSFER" ? (
          <TransferDirectionFields
            accounts={accounts}
            sourceAccount={sourceAccount}
            destAccount={destAccount}
            crossCurrency={crossCurrency}
            isEditing={isEditing}
            isRateLoading={isRateLoading}
            rateError={rateError}
            overridden={overridden}
            onRevertToFetchedRate={revert}
            onDestinationAmountEdited={markOverridden}
            conversionLine={conversionLine}
            register={register}
            errors={errors}
            labels={{
              account: t("fields.account"),
              from: t("transfer.from"),
              to: t("transfer.to"),
              amount: t("fields.amount"),
              destinationAmount: t("fields.destinationAmount"),
              rateLoading: t("transfer.rateLoading"),
              rateFetchFailed: t("transfer.rateFetchFailed"),
              useFetchedRate: t("transfer.useFetchedRate"),
              destinationAmountError: destinationAmountErrorMessage,
              transferAccountError: transferAccountIdErrorMessage,
            }}
          />
        ) : (
          <>
            <Field label={t("fields.account")} error={errors.accountId?.message}>
              <select {...register("accountId")} disabled={isEditing} className={fieldInputCls(isEditing)}>
                {accounts.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
              </select>
            </Field>
            <Field label={t("fields.amount")} error={errors.amount?.message}>
              <input type="number" step="0.01" {...register("amount")} className={inputCls} />
            </Field>
          </>
        )}

        <Field label={t("fields.date")} error={errors.transactionDate?.message}>
          <input type="date" {...register("transactionDate")} className={inputCls} />
        </Field>
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
