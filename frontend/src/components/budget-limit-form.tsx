"use client";

import { useEffect } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { cn } from "@/lib/utils";

export type BudgetPeriod = "MONTHLY" | "YEARLY";

const CURRENCY_FALLBACK = ["USD", "VND", "EUR"];

const limitSchema = z.object({
  amount: z.string().min(1, "Enter an amount"),
  period: z.enum(["MONTHLY", "YEARLY"]),
  currency: z.string().regex(/^[A-Z]{3}$/, "Select a currency"),
});

type LimitValues = z.infer<typeof limitSchema>;

export interface BudgetLimitPayload {
  amountLimit: string;
  period: BudgetPeriod;
  startDate: string;
  currency: string;
}

interface BudgetLimitFormProps {
  initialAmount?: string;
  initialPeriod?: BudgetPeriod;
  /** Pre-selected currency (e.g. the currently selected currency in the manager). */
  initialCurrency?: string;
  /** Available currency options sourced from the user's accounts. Falls back to USD/VND/EUR. */
  availableCurrencies?: string[];
  onSubmit: (data: BudgetLimitPayload) => void;
  onCancel: () => void;
  isPending: boolean;
  submitLabel?: string;
  className?: string;
}

export function getPeriodStart(period: BudgetPeriod): string {
  const today = new Date();
  if (period === "MONTHLY") {
    return `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, "0")}-01`;
  }
  return `${today.getFullYear()}-01-01`;
}

const inputCls =
  "rounded-lg border border-border bg-card px-2 py-1 text-xs text-foreground focus:outline-none focus:ring-2 focus:ring-primary/40 focus:border-primary/40 transition-colors";

export function BudgetLimitForm({
  initialAmount = "",
  initialPeriod = "MONTHLY",
  initialCurrency = "USD",
  availableCurrencies,
  onSubmit,
  onCancel,
  isPending,
  submitLabel = "Save",
  className,
}: BudgetLimitFormProps) {
  const currencies = availableCurrencies?.length ? availableCurrencies : CURRENCY_FALLBACK;

  const form = useForm<LimitValues>({
    resolver: zodResolver(limitSchema),
    defaultValues: { amount: initialAmount, period: initialPeriod, currency: initialCurrency },
  });

  useEffect(() => {
    form.reset({ amount: initialAmount, period: initialPeriod, currency: initialCurrency });
  }, [form, initialAmount, initialPeriod, initialCurrency]);

  const handleSubmit = (values: LimitValues) => {
    onSubmit({
      amountLimit: values.amount,
      period: values.period,
      startDate: getPeriodStart(values.period),
      currency: values.currency,
    });
  };

  return (
    <form onSubmit={form.handleSubmit(handleSubmit)} className={cn("flex flex-wrap items-end gap-2 pt-1", className)}>
      <div>
        <label className="mb-1 block text-xs font-medium uppercase tracking-wide text-muted-foreground">Amount</label>
        <input type="number" step="0.01" {...form.register("amount")} className={`w-28 ${inputCls}`} />
        {form.formState.errors.amount && (
          <p className="mt-0.5 text-xs text-rose-600 dark:text-rose-400">{form.formState.errors.amount.message}</p>
        )}
      </div>
      <div>
        <label className="mb-1 block text-xs font-medium uppercase tracking-wide text-muted-foreground">Period</label>
        <select {...form.register("period")} className={inputCls}>
          <option value="MONTHLY">Monthly</option>
          <option value="YEARLY">Yearly</option>
        </select>
      </div>
      <div>
        <label className="mb-1 block text-xs font-medium uppercase tracking-wide text-muted-foreground">Currency</label>
        <select {...form.register("currency")} className={inputCls}>
          {currencies.map((c) => (
            <option key={c} value={c}>{c}</option>
          ))}
        </select>
        {form.formState.errors.currency && (
          <p className="mt-0.5 text-xs text-rose-600 dark:text-rose-400">{form.formState.errors.currency.message}</p>
        )}
      </div>
      <div className="flex gap-1">
        <button
          type="submit"
          disabled={isPending}
          className="rounded-lg bg-emerald-500/10 border border-emerald-500/20 px-3 py-1 text-xs text-emerald-600 dark:text-emerald-400 hover:bg-emerald-500/20 disabled:opacity-50 transition-colors"
        >
          {isPending ? "Saving..." : submitLabel}
        </button>
        <button
          type="button"
          onClick={onCancel}
          className="rounded-lg border border-border px-3 py-1 text-xs text-muted-foreground hover:bg-secondary transition-colors"
        >
          Cancel
        </button>
      </div>
    </form>
  );
}
