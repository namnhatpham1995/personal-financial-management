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
import { useLocale, useTranslations } from "next-intl";
import { Plus, Trash2, Pause, Play } from "lucide-react";
import { cn } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import { Card } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { useIdempotencyKey } from "@/lib/use-idempotency-key";
import { getIdempotencyErrorCode } from "@/lib/idempotency-error";
import { useTransactionTypeLabel, useFrequencyLabel } from "@/lib/enum-labels";

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

const inputCls =
  "w-full rounded-md border border-border bg-card px-3.5 py-2.5 text-base text-foreground placeholder:text-muted-foreground/50 focus:outline-none focus:ring-2 focus:ring-primary/40 focus:border-primary/40 transition-colors";

export function RecurringTab() {
  const qc = useQueryClient();
  const t = useTranslations("transactions.recurring");
  const tCommon = useTranslations("common");
  const locale = useLocale();
  const getTypeLabel = useTransactionTypeLabel();
  const getFrequencyLabel = useFrequencyLabel();
  const [showForm, setShowForm] = useState(false);

  const { data: items = [], isLoading } = useQuery({
    queryKey: ["recurring"],
    queryFn: recurringService.list,
  });

  const { data: accounts = [] } = useQuery({
    queryKey: ["accounts"],
    queryFn: accountService.list,
  });

  const createIdempotency = useIdempotencyKey(null);

  const createMutation = useMutation({
    mutationFn: (data: CreateRecurringPayload) =>
      recurringService.create(data, createIdempotency.resolve(data)),
    onSuccess: () => {
      createIdempotency.clear();
      qc.invalidateQueries({ queryKey: ["recurring"] });
      setShowForm(false);
      toast.success(t("toast.created"));
    },
    onError: (err) => {
      const idempotencyCode = getIdempotencyErrorCode(err);
      if (idempotencyCode === "idempotency_key_conflict") {
        createIdempotency.clear();
        toast.error(t("toast.createFailed"));
      } else if (idempotencyCode === "operation_in_progress") {
        toast.error(tCommon("operationInProgress"));
      } else {
        toast.error(t("toast.createFailed"));
      }
    },
  });

  const pauseMutation = useMutation({
    mutationFn: (id: number) => recurringService.pause(id),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["recurring"] }); toast.success(t("toast.paused")); },
  });

  const resumeMutation = useMutation({
    mutationFn: (id: number) => recurringService.resume(id),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["recurring"] }); toast.success(t("toast.resumed")); },
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => recurringService.delete(id),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ["recurring"] }); toast.success(t("toast.deleted")); },
  });

  const { register, handleSubmit, reset, formState: { errors } } =
    useForm<FormValues>({ resolver: zodResolver(schema) });

  const submitCreate = handleSubmit((v) => {
    // Defense-in-depth against a double-click/double-submit race: react-hook-form's
    // isSubmitting resolves almost instantly for a sync callback, so the disabled prop
    // below relies on the mutation's own isPending — this guard covers the gap between
    // the first click registering and that state flowing back into a re-render.
    if (createMutation.isPending) return;
    createMutation.mutate(v);
  });

  return (
    <div className="space-y-6">
      <div className="flex justify-end">
        <Button onClick={() => { setShowForm(!showForm); reset(); }}>
          <Plus className="h-4 w-4" /> {t("newRule")}
        </Button>
      </div>

      {showForm && (
        <Card className="p-5">
          <h2 className="mb-4 font-semibold tracking-tight text-foreground">{t("newRuleTitle")}</h2>
          <form
            onSubmit={submitCreate}
            className="grid grid-cols-1 gap-4 sm:grid-cols-2"
          >
            <Field label={t("fields.account")} error={errors.accountId?.message}>
              <select {...register("accountId")} className={inputCls}>
                {accounts.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
              </select>
            </Field>
            <Field label={t("fields.type")} error={errors.transactionType?.message}>
              <select {...register("transactionType")} className={inputCls}>
                {["INCOME", "EXPENSE", "TRANSFER"].map((type) => <option key={type}>{type}</option>)}
              </select>
            </Field>
            <Field label={t("fields.amount")} error={errors.amount?.message}>
              <input type="number" step="0.01" {...register("amount")} className={inputCls} />
            </Field>
            <Field label={t("fields.frequency")} error={errors.frequency?.message}>
              <select {...register("frequency")} className={inputCls}>
                {["DAILY", "WEEKLY", "MONTHLY", "YEARLY"].map((f) => <option key={f} value={f}>{getFrequencyLabel(f)}</option>)}
              </select>
            </Field>
            <Field label={t("fields.everyNPeriods")} error={errors.intervalValue?.message}>
              <input type="number" defaultValue={1} {...register("intervalValue")} className={inputCls} />
            </Field>
            <Field label={t("fields.startDate")} error={errors.startDate?.message}>
              <input type="date" {...register("startDate")} className={inputCls} />
            </Field>
            <Field label={t("fields.note")} error={errors.note?.message}>
              <input {...register("note")} className={inputCls} />
            </Field>
            <div className="flex gap-2 sm:col-span-2">
              <Button type="submit" disabled={createMutation.isPending}>
                {tCommon("save")}
              </Button>
              <Button type="button" variant="secondary" onClick={() => setShowForm(false)}>
                {tCommon("cancel")}
              </Button>
            </div>
          </form>
        </Card>
      )}

      {isLoading ? (
        <p className="text-muted-foreground">{tCommon("loading")}</p>
      ) : (
        <div className="grid gap-4 sm:grid-cols-2">
          {items.length === 0 ? (
            <p className="text-sm text-muted-foreground sm:col-span-2">{t("empty")}</p>
          ) : (
            items.map((item) => (
              <Card
                key={item.id}
                className={cn("p-5", !item.active && "opacity-50")}
              >
                <div className="flex items-start justify-between gap-2">
                  <div>
                    <div className="flex items-center gap-2">
                      <Badge variant={item.transactionType === "INCOME" ? "income" : item.transactionType === "EXPENSE" ? "expense" : "transfer"}>
                        {getTypeLabel(item.transactionType)}
                      </Badge>
                      {!item.active && <Badge variant="neutral">{t("pausedBadge")}</Badge>}
                    </div>
                    <p className="mt-2 font-mono tabular-nums text-lg font-bold text-foreground">
                      {formatAmount(item.amount, locale)}
                    </p>
                    <p className="text-sm text-muted-foreground">
                      {t("every", {
                        intervalValue: item.intervalValue,
                        frequency: item.frequency.toLowerCase(),
                        accountName: item.accountName,
                      })}
                    </p>
                    <p className="mt-1 font-mono tabular-nums text-xs text-muted-foreground">
                      {t("next", { date: formatDate(item.nextRunDate, locale) })}
                    </p>
                  </div>
                  <div className="flex items-center gap-1">
                    <button
                      onClick={() => item.active ? pauseMutation.mutate(item.id) : resumeMutation.mutate(item.id)}
                      className="inline-flex min-h-11 min-w-11 items-center justify-center rounded-sm text-muted-foreground hover:bg-secondary hover:text-foreground transition-colors"
                    >
                      {item.active ? <Pause className="h-4 w-4" /> : <Play className="h-4 w-4" />}
                    </button>
                    <button
                      onClick={() => deleteMutation.mutate(item.id)}
                      className="inline-flex min-h-11 min-w-11 items-center justify-center rounded-sm text-muted-foreground hover:bg-destructive/10 hover:text-destructive transition-colors"
                    >
                      <Trash2 className="h-4 w-4" />
                    </button>
                  </div>
                </div>
              </Card>
            ))
          )}
        </div>
      )}
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
