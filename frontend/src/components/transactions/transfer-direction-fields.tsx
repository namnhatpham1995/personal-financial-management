"use client";

import { ArrowRight } from "lucide-react";
import type { UseFormRegister, FieldErrors } from "react-hook-form";
import { Button } from "@/components/ui/button";
import { Field, fieldInputCls, inputCls } from "@/components/transactions/transaction-field";
import type { Account } from "@/services/account-service";
import type { TransactionFormValues } from "@/app/dashboard/transactions/transaction-form-schema";

interface Props {
  accounts: Account[];
  sourceAccount: Account | undefined;
  destAccount: Account | undefined;
  crossCurrency: boolean;
  isEditing: boolean;
  isConverting: boolean;
  convertError: boolean;
  conversionLine: { amountText: string; destText: string; rateText: string; asOfText: string | null } | null;
  register: UseFormRegister<TransactionFormValues>;
  errors: FieldErrors<TransactionFormValues>;
  onAutoConvert: () => void;
  labels: {
    account: string;
    from: string;
    to: string;
    amount: string;
    destinationAmount: string;
    autoConvert: string;
    autoConvertFailed: string;
    destinationAmountError?: string;
    transferAccountError?: string;
  };
}

/**
 * Directional From -> To layout for TRANSFER entries. Source account/amount
 * flow visually into the destination account (and, cross-currency, the
 * destination amount) instead of being scattered across a generic grid.
 */
export function TransferDirectionFields({
  accounts,
  sourceAccount,
  destAccount,
  crossCurrency,
  isEditing,
  isConverting,
  convertError,
  conversionLine,
  register,
  errors,
  onAutoConvert,
  labels,
}: Props) {
  // Only the destination list excludes the current source: hiding the current
  // destination from the source list too would make it impossible to ever pick
  // it as the new source, which is exactly the transition that should instead
  // clear the destination (see the effect in transaction-form.tsx).
  const destinationOptions = accounts.filter((a) => a.id !== sourceAccount?.id);

  return (
    <div className="sm:col-span-2 grid grid-cols-1 items-start gap-4 sm:grid-cols-[1fr_auto_1fr]">
      <div className="space-y-3">
        <span className="block text-xs font-medium uppercase tracking-wide text-muted-foreground">
          {labels.from}
        </span>
        <Field label={labels.account} error={errors.accountId?.message}>
          <select {...register("accountId")} disabled={isEditing} className={fieldInputCls(isEditing)}>
            {accounts.map((a) => (
              <option key={a.id} value={a.id}>
                {a.name}
              </option>
            ))}
          </select>
        </Field>
        <Field label={sourceAccount ? `${labels.amount} (${sourceAccount.currency})` : labels.amount} error={errors.amount?.message}>
          <input type="number" step="0.01" {...register("amount")} className={inputCls} />
        </Field>
      </div>

      <div className="hidden items-center justify-center pt-8 sm:flex">
        <ArrowRight className="h-5 w-5 text-muted-foreground" aria-hidden="true" />
      </div>
      <div className="flex justify-center sm:hidden" aria-hidden="true">
        <ArrowRight className="h-5 w-5 rotate-90 text-muted-foreground" />
      </div>

      <div className="space-y-3">
        <span className="block text-xs font-medium uppercase tracking-wide text-muted-foreground">
          {labels.to}
        </span>
        <Field label={labels.account} error={labels.transferAccountError ?? errors.transferAccountId?.message}>
          <select {...register("transferAccountId")} disabled={isEditing} className={fieldInputCls(isEditing)}>
            {destinationOptions.map((a) => (
              <option key={a.id} value={a.id}>
                {a.name}
              </option>
            ))}
          </select>
        </Field>
        {crossCurrency && destAccount && (
          <Field
            label={`${labels.destinationAmount} (${destAccount.currency})`}
            error={labels.destinationAmountError}
          >
            <div className="flex gap-2">
              {/* step="any": Auto Convert fills scale-4 precision (matching backend storage),
                  which a step="0.01" input would silently reject via native HTML5 validation. */}
              <input type="number" step="any" {...register("destinationAmount")} className={inputCls} />
              <Button type="button" variant="secondary" disabled={isConverting} onClick={onAutoConvert}>
                {labels.autoConvert}
              </Button>
            </div>
            {convertError && <p className="mt-1 text-xs text-destructive">{labels.autoConvertFailed}</p>}
            {conversionLine && (
              <p className="mt-1.5 font-mono tabular-nums text-xs text-muted-foreground">
                {conversionLine.amountText} {sourceAccount!.currency} → {conversionLine.destText} {destAccount.currency}
                {" @ "}
                {conversionLine.rateText}
                {conversionLine.asOfText ? ` (${conversionLine.asOfText})` : ""}
              </p>
            )}
          </Field>
        )}
      </div>
    </div>
  );
}
