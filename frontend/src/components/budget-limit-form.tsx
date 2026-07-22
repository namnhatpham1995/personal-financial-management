"use client";

import { useEffect, useMemo } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useTranslations } from "next-intl";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";

export type BudgetPeriod = "MONTHLY" | "YEARLY";

const CURRENCY_FALLBACK = ["USD", "VND", "EUR"];

function createLimitSchema(t: (key: string) => string) {
  return z.object({
    amount: z.string().min(1, t("validation.enterAmount")),
    period: z.enum(["MONTHLY", "YEARLY"]),
    currency: z.string().regex(/^[A-Z]{3}$/, t("validation.selectCurrency")),
  });
}

type LimitValues = z.infer<ReturnType<typeof createLimitSchema>>;

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
  /** When true, currency is fixed to initialCurrency and shown read-only (no select). */
  lockCurrency?: boolean;
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
  lockCurrency = false,
  onSubmit,
  onCancel,
  isPending,
  submitLabel,
  className,
}: BudgetLimitFormProps) {
  const t = useTranslations("budgets");
  const tCommon = useTranslations("common");
  const currencies = availableCurrencies?.length ? availableCurrencies : CURRENCY_FALLBACK;
  const limitSchema = useMemo(() => createLimitSchema(t), [t]);

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
        <label className="mb-1 block text-xs font-medium uppercase tracking-wide text-muted-foreground">{t("fields.amount")}</label>
        <input type="number" step="0.01" {...form.register("amount")} className={`w-28 ${inputCls}`} />
        {form.formState.errors.amount && (
          <p className="mt-0.5 text-xs text-rose-600 dark:text-rose-400">{form.formState.errors.amount.message}</p>
        )}
      </div>
      <div>
        <label className="mb-1 block text-xs font-medium uppercase tracking-wide text-muted-foreground">{t("fields.period")}</label>
        <select {...form.register("period")} className={inputCls}>
          <option value="MONTHLY">{t("period.monthly")}</option>
          <option value="YEARLY">{t("period.yearly")}</option>
        </select>
      </div>
      {lockCurrency ? (
        <input type="hidden" {...form.register("currency")} />
      ) : (
        <div>
          <label className="mb-1 block text-xs font-medium uppercase tracking-wide text-muted-foreground">{t("fields.currency")}</label>
          <select {...form.register("currency")} className={inputCls}>
            {currencies.map((c) => (
              // enum-label-coverage-ignore: currency codes (USD, EUR, …) are verbatim, never translated
              <option key={c} value={c}>{c}</option>
            ))}
          </select>
          {form.formState.errors.currency && (
            <p className="mt-0.5 text-xs text-rose-600 dark:text-rose-400">{form.formState.errors.currency.message}</p>
          )}
        </div>
      )}
      <div className="flex gap-1">
        <Button type="submit" size="sm" className="py-1" disabled={isPending}>
          {isPending ? t("saving") : submitLabel ?? tCommon("save")}
        </Button>
        <Button type="button" variant="secondary" size="sm" className="py-1" onClick={onCancel}>
          {tCommon("cancel")}
        </Button>
      </div>
    </form>
  );
}
