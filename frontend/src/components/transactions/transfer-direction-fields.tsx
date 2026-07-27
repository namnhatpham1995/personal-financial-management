"use client";

import { ArrowRight, Loader2, RefreshCw } from "lucide-react";
import type { UseFormRegister, FieldErrors } from "react-hook-form";
import { Button } from "@/components/ui/button";
import { Field, fieldInputCls, inputCls } from "@/components/transactions/transaction-field";
import type { Account } from "@/services/account-service";
import type { TransactionFormValues } from "@/app/dashboard/transactions/transaction-form-schema";
import type { ConversionLine } from "@/components/transactions/use-auto-conversion";

interface Props {
  accounts: Account[];
  sourceAccount: Account | undefined;
  destAccount: Account | undefined;
  crossCurrency: boolean;
  isEditing: boolean;
  isRateLoading: boolean;
  rateError: boolean;
  overridden: boolean;
  onRevertToFetchedRate: () => void;
  onDestinationAmountEdited: () => void;
  conversionLine: ConversionLine | null;
  register: UseFormRegister<TransactionFormValues>;
  errors: FieldErrors<TransactionFormValues>;
  labels: {
    account: string;
    from: string;
    to: string;
    amount: string;
    destinationAmount: string;
    rateLoading: string;
    rateFetchFailed: string;
    customAmount: string;
    restoreSuggestedAmount: string;
    customRate: string;
    fetchedRate: string;
    suggestedAmount: string;
    destinationAmountError?: string;
    transferAccountError?: string;
  };
}

/**
 * Directional From -> To layout for TRANSFER entries. Source account/amount
 * flow visually into the destination account (and, cross-currency, the
 * destination amount) instead of being scattered across a generic grid.
 * The destination amount auto-fills from a debounced exchange-rate fetch;
 * a manual edit overrides it until the user explicitly restores the suggestion.
 */
export function TransferDirectionFields({
  accounts,
  sourceAccount,
  destAccount,
  crossCurrency,
  isEditing,
  isRateLoading,
  rateError,
  overridden,
  onRevertToFetchedRate,
  onDestinationAmountEdited,
  conversionLine,
  register,
  errors,
  labels,
}: Props) {
  // Only the destination list excludes the current source: hiding the current
  // destination from the source list too would make it impossible to ever pick
  // it as the new source, which is exactly the transition that should instead
  // clear the destination (see the effect in transaction-form.tsx).
  const destinationOptions = accounts.filter((a) => a.id !== sourceAccount?.id);
  const destinationField = register("destinationAmount");

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
            <div className="relative">
              <input
                type="number"
                step="any"
                {...destinationField}
                onChange={(e) => {
                  destinationField.onChange(e);
                  onDestinationAmountEdited();
                }}
                className={inputCls}
              />
              {isRateLoading && (
                <Loader2
                  className="absolute right-3 top-1/2 h-4 w-4 -translate-y-1/2 animate-spin text-muted-foreground"
                  aria-label={labels.rateLoading}
                />
              )}
            </div>
            {rateError && <p className="mt-1 text-xs text-destructive">{labels.rateFetchFailed}</p>}
            {overridden && (
              <div
                aria-live="polite"
                className="mt-2 flex flex-wrap items-center gap-2 rounded-md border border-primary/20 bg-primary/5 px-3 py-2"
              >
                <span className="text-sm font-medium text-foreground">{labels.customAmount}</span>
                <Button
                  type="button"
                  variant="primary"
                  size="md"
                  disabled={isRateLoading}
                  onClick={onRevertToFetchedRate}
                  className="w-full rounded-full sm:w-auto"
                >
                  <RefreshCw className="h-4 w-4" aria-hidden="true" />
                  {labels.restoreSuggestedAmount}
                </Button>
              </div>
            )}
            {conversionLine && (
              <div className="mt-2 space-y-1 font-mono tabular-nums text-xs text-muted-foreground">
                <p>
                  {conversionLine.mode === "custom" && (
                    <span className="font-sans font-medium text-foreground">{labels.customRate}: </span>
                  )}
                  {conversionLine.amountText} → {conversionLine.destText} ({conversionLine.rateText})
                  {conversionLine.asOfText ? ` · ${conversionLine.asOfText}` : ""}
                </p>
                {conversionLine.mode === "custom" && conversionLine.fetchedRateText && (
                  <p>
                    <span className="font-sans font-medium text-foreground">{labels.fetchedRate}: </span>
                    {conversionLine.fetchedDestinationText && `${labels.suggestedAmount}: ${conversionLine.fetchedDestinationText} · `}
                    {conversionLine.fetchedRateText}
                    {conversionLine.fetchedAsOfText ? ` · ${conversionLine.fetchedAsOfText}` : ""}
                  </p>
                )}
              </div>
            )}
          </Field>
        )}
      </div>
    </div>
  );
}
