"use client";

import { useEffect } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { cn } from "@/lib/utils";

export type BudgetPeriod = "MONTHLY" | "YEARLY";

const limitSchema = z.object({
  amount: z.string().min(1, "Enter an amount"),
  period: z.enum(["MONTHLY", "YEARLY"]),
});

type LimitValues = z.infer<typeof limitSchema>;

export interface BudgetLimitPayload {
  amountLimit: string;
  period: BudgetPeriod;
  startDate: string;
}

interface BudgetLimitFormProps {
  initialAmount?: string;
  initialPeriod?: BudgetPeriod;
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
  "rounded-lg border border-slate-800/60 bg-slate-900/60 px-2 py-1 text-xs text-slate-200 focus:outline-none focus:ring-2 focus:ring-emerald-500/40 focus:border-emerald-500/40 transition-colors";

export function BudgetLimitForm({
  initialAmount = "",
  initialPeriod = "MONTHLY",
  onSubmit,
  onCancel,
  isPending,
  submitLabel = "Save",
  className,
}: BudgetLimitFormProps) {
  const form = useForm<LimitValues>({
    resolver: zodResolver(limitSchema),
    defaultValues: { amount: initialAmount, period: initialPeriod },
  });

  useEffect(() => {
    form.reset({ amount: initialAmount, period: initialPeriod });
  }, [form, initialAmount, initialPeriod]);

  const handleSubmit = (values: LimitValues) => {
    onSubmit({
      amountLimit: values.amount,
      period: values.period,
      startDate: getPeriodStart(values.period),
    });
  };

  return (
    <form onSubmit={form.handleSubmit(handleSubmit)} className={cn("flex flex-wrap items-end gap-2 pt-1", className)}>
      <div>
        <label className="mb-1 block text-xs font-medium uppercase tracking-wide text-slate-500">Amount</label>
        <input type="number" step="0.01" {...form.register("amount")} className={`w-28 ${inputCls}`} />
        {form.formState.errors.amount && (
          <p className="mt-0.5 text-xs text-rose-400">{form.formState.errors.amount.message}</p>
        )}
      </div>
      <div>
        <label className="mb-1 block text-xs font-medium uppercase tracking-wide text-slate-500">Period</label>
        <select {...form.register("period")} className={inputCls}>
          <option value="MONTHLY">Monthly</option>
          <option value="YEARLY">Yearly</option>
        </select>
      </div>
      <div className="flex gap-1">
        <button
          type="submit"
          disabled={isPending}
          className="rounded-lg bg-emerald-500/10 border border-emerald-500/20 px-3 py-1 text-xs text-emerald-400 hover:bg-emerald-500/20 disabled:opacity-50 transition-colors"
        >
          {isPending ? "Saving..." : submitLabel}
        </button>
        <button
          type="button"
          onClick={onCancel}
          className="rounded-lg border border-slate-800/60 px-3 py-1 text-xs text-slate-400 hover:bg-slate-800/60 transition-colors"
        >
          Cancel
        </button>
      </div>
    </form>
  );
}
