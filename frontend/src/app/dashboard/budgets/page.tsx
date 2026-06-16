"use client";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { budgetService, CreateBudgetPayload } from "@/services/budget-service";
import { formatCurrency } from "@/lib/utils";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { toast } from "sonner";
import { Plus, Trash2 } from "lucide-react";
import { cn } from "@/lib/utils";

const schema = z.object({
  name: z.string().min(1),
  limitAmount: z.string().min(1),
  period: z.enum(["MONTHLY", "YEARLY"]),
});

type FormValues = z.infer<typeof schema>;

export default function BudgetsPage() {
  const qc = useQueryClient();
  const [showForm, setShowForm] = useState(false);

  const { data: budgets = [], isLoading } = useQuery({
    queryKey: ["budgets"],
    queryFn: budgetService.list,
  });

  const createMutation = useMutation({
    mutationFn: (data: CreateBudgetPayload) => budgetService.create(data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["budgets"] });
      setShowForm(false);
      toast.success("Budget created");
    },
    onError: () => toast.error("Failed to create budget"),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => budgetService.delete(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["budgets"] });
      toast.success("Budget deleted");
    },
  });

  const { register, handleSubmit, reset, formState: { errors, isSubmitting } } =
    useForm<FormValues>({ resolver: zodResolver(schema) });

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">Budgets</h1>
        <button
          onClick={() => { setShowForm(!showForm); reset(); }}
          className="flex items-center gap-2 rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90"
        >
          <Plus className="h-4 w-4" /> New Budget
        </button>
      </div>

      {showForm && (
        <div className="rounded-xl border border-border bg-card p-5">
          <h2 className="mb-4 font-semibold">Create Budget</h2>
          <form onSubmit={handleSubmit((v) => createMutation.mutate(v))} className="grid grid-cols-2 gap-4">
            <Field label="Name" error={errors.name?.message}>
              <input {...register("name")} className={inputCls} />
            </Field>
            <Field label="Period" error={errors.period?.message}>
              <select {...register("period")} className={inputCls}>
                <option>MONTHLY</option>
                <option>YEARLY</option>
              </select>
            </Field>
            <Field label="Limit Amount" error={errors.limitAmount?.message}>
              <input type="number" step="0.01" {...register("limitAmount")} className={inputCls} />
            </Field>
            <div className="col-span-2 flex gap-2">
              <button type="submit" disabled={isSubmitting} className="rounded-md bg-primary px-4 py-2 text-sm text-primary-foreground hover:bg-primary/90 disabled:opacity-50">Save</button>
              <button type="button" onClick={() => setShowForm(false)} className="rounded-md border px-4 py-2 text-sm hover:bg-accent">Cancel</button>
            </div>
          </form>
        </div>
      )}

      {isLoading ? (
        <p className="text-muted-foreground">Loading…</p>
      ) : (
        <div className="grid gap-4 sm:grid-cols-2">
          {budgets.map((b) => {
            const pct = Math.min(Number(b.percentUsed), 100);
            return (
              <div key={b.id} className={cn("rounded-xl border bg-card p-5 shadow-sm", b.overBudget ? "border-destructive/40" : "border-border")}>
                <div className="flex items-start justify-between">
                  <div>
                    <p className="font-semibold">{b.name}</p>
                    <p className="text-xs text-muted-foreground">{b.period} · {b.categoryName ?? "All categories"}</p>
                  </div>
                  <button onClick={() => deleteMutation.mutate(b.id)} className="text-muted-foreground hover:text-destructive">
                    <Trash2 className="h-4 w-4" />
                  </button>
                </div>

                <div className="mt-3">
                  <div className="mb-1 flex justify-between text-sm">
                    <span>{formatCurrency(b.spent)}</span>
                    <span className="text-muted-foreground">of {formatCurrency(b.limitAmount)}</span>
                  </div>
                  <div className="h-2 overflow-hidden rounded-full bg-muted">
                    <div
                      className={cn("h-full rounded-full transition-all", b.overBudget ? "bg-destructive" : "bg-primary")}
                      style={{ width: `${pct}%` }}
                    />
                  </div>
                  <p className={cn("mt-1 text-xs", b.overBudget ? "text-destructive" : "text-muted-foreground")}>
                    {b.overBudget
                      ? `Over by ${formatCurrency(Math.abs(Number(b.remaining)))}`
                      : `${formatCurrency(b.remaining)} remaining`}
                  </p>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

const inputCls = "w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring";

function Field({ label, error, children }: { label: string; error?: string; children: React.ReactNode }) {
  return (
    <div>
      <label className="mb-1 block text-sm font-medium">{label}</label>
      {children}
      {error && <p className="mt-1 text-xs text-destructive">{error}</p>}
    </div>
  );
}
