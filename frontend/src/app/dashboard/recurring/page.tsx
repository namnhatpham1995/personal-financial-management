"use client";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { recurringService, CreateRecurringPayload } from "@/services/recurring-service";
import { accountService } from "@/services/account-service";
import { formatAmount, formatDate } from "@/lib/utils";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { toast } from "sonner";
import { Plus, Trash2, Pause, Play } from "lucide-react";
import { cn } from "@/lib/utils";

const schema = z.object({
  accountId: z.coerce.number(),
  transactionType: z.enum(["INCOME", "EXPENSE", "TRANSFER"]),
  amount: z.string().min(1),
  frequency: z.enum(["DAILY", "WEEKLY", "MONTHLY", "YEARLY"]),
  intervalValue: z.coerce.number().min(1).default(1),
  startDate: z.string().min(1),
  note: z.string().optional(),
});

type FormValues = z.infer<typeof schema>;

export default function RecurringPage() {
  const qc = useQueryClient();
  const [showForm, setShowForm] = useState(false);

  const { data: items = [], isLoading } = useQuery({
    queryKey: ["recurring"],
    queryFn: recurringService.list,
  });

  const { data: accounts = [] } = useQuery({
    queryKey: ["accounts"],
    queryFn: accountService.list,
  });

  const createMutation = useMutation({
    mutationFn: (data: CreateRecurringPayload) => recurringService.create(data),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["recurring"] }); setShowForm(false); toast.success("Created"); },
    onError: () => toast.error("Failed to create"),
  });

  const pauseMutation = useMutation({
    mutationFn: (id: number) => recurringService.pause(id),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["recurring"] }); toast.success("Paused"); },
  });

  const resumeMutation = useMutation({
    mutationFn: (id: number) => recurringService.resume(id),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["recurring"] }); toast.success("Resumed"); },
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => recurringService.delete(id),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["recurring"] }); toast.success("Deleted"); },
  });

  const { register, handleSubmit, reset, formState: { errors, isSubmitting } } =
    useForm<FormValues>({ resolver: zodResolver(schema) });

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">Recurring Transactions</h1>
        <button
          onClick={() => { setShowForm(!showForm); reset(); }}
          className="flex items-center gap-2 rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90"
        >
          <Plus className="h-4 w-4" /> New
        </button>
      </div>

      {showForm && (
        <div className="rounded-xl border border-border bg-card p-5">
          <h2 className="mb-4 font-semibold">New Recurring Rule</h2>
          <form onSubmit={handleSubmit((v) => createMutation.mutate(v))} className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Field label="Account" error={errors.accountId?.message}>
              <select {...register("accountId")} className={inputCls}>
                {accounts.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
              </select>
            </Field>
            <Field label="Type" error={errors.transactionType?.message}>
              <select {...register("transactionType")} className={inputCls}>
                {["INCOME", "EXPENSE", "TRANSFER"].map((t) => <option key={t}>{t}</option>)}
              </select>
            </Field>
            <Field label="Amount" error={errors.amount?.message}>
              <input type="number" step="0.01" {...register("amount")} className={inputCls} />
            </Field>
            <Field label="Frequency" error={errors.frequency?.message}>
              <select {...register("frequency")} className={inputCls}>
                {["DAILY", "WEEKLY", "MONTHLY", "YEARLY"].map((f) => <option key={f}>{f}</option>)}
              </select>
            </Field>
            <Field label="Every N periods" error={errors.intervalValue?.message}>
              <input type="number" defaultValue={1} {...register("intervalValue")} className={inputCls} />
            </Field>
            <Field label="Start Date" error={errors.startDate?.message}>
              <input type="date" {...register("startDate")} className={inputCls} />
            </Field>
            <Field label="Note" error={errors.note?.message}>
              <input {...register("note")} className={inputCls} />
            </Field>
            <div className="sm:col-span-2 flex gap-2">
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
          {items.map((item) => (
            <div key={item.id} className={cn("rounded-xl border bg-card p-5 shadow-sm", !item.active ? "opacity-60" : "border-border")}>
              <div className="flex items-start justify-between gap-2">
                <div>
                  <div className="flex items-center gap-2">
                    <span className={cn("rounded-full px-2 py-0.5 text-xs font-medium",
                      item.transactionType === "INCOME" ? "bg-green-100 text-green-700" :
                      item.transactionType === "EXPENSE" ? "bg-red-100 text-red-700" :
                      "bg-blue-100 text-blue-700"
                    )}>{item.transactionType}</span>
                    {!item.active && <span className="rounded-full bg-muted px-2 py-0.5 text-xs text-muted-foreground">Paused</span>}
                  </div>
                  <p className="mt-1 text-lg font-bold">{formatAmount(item.amount)}</p>
                  <p className="text-sm text-muted-foreground">
                    Every {item.intervalValue > 1 ? `${item.intervalValue} ` : ""}{item.frequency.toLowerCase()} · {item.accountName}
                  </p>
                  <p className="mt-1 text-xs text-muted-foreground">Next: {formatDate(item.nextRunDate)}</p>
                </div>
                <div className="flex items-center gap-1">
                  <button
                    onClick={() => item.active ? pauseMutation.mutate(item.id) : resumeMutation.mutate(item.id)}
                    className="rounded-md p-1.5 text-muted-foreground hover:bg-accent"
                  >
                    {item.active ? <Pause className="h-4 w-4" /> : <Play className="h-4 w-4" />}
                  </button>
                  <button onClick={() => deleteMutation.mutate(item.id)} className="rounded-md p-1.5 text-muted-foreground hover:text-destructive">
                    <Trash2 className="h-4 w-4" />
                  </button>
                </div>
              </div>
            </div>
          ))}
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
