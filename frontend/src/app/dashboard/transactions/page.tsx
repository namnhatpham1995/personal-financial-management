"use client";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { transactionService, TransactionFilters, CreateTransactionPayload } from "@/services/transaction-service";
import { accountService } from "@/services/account-service";
import { formatCurrency, formatDate } from "@/lib/utils";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { toast } from "sonner";
import { Plus, Trash2, ChevronLeft, ChevronRight } from "lucide-react";
import { cn } from "@/lib/utils";

const schema = z.object({
  accountId: z.coerce.number(),
  transactionType: z.enum(["INCOME", "EXPENSE", "TRANSFER"]),
  amount: z.string().min(1),
  transactionDate: z.string().min(1),
  note: z.string().optional(),
  transferAccountId: z.coerce.number().optional(),
});

type FormValues = z.infer<typeof schema>;

export default function TransactionsPage() {
  const qc = useQueryClient();
  const [showForm, setShowForm] = useState(false);
  const [filters, setFilters] = useState<TransactionFilters>({ page: 0, size: 20 });

  const { data: pageData, isLoading, isError } = useQuery({
    queryKey: ["transactions", filters],
    queryFn: () => transactionService.list(filters),
  });

  const { data: accounts = [] } = useQuery({
    queryKey: ["accounts"],
    queryFn: accountService.list,
  });

  const createMutation = useMutation({
    mutationFn: (data: CreateTransactionPayload) => transactionService.create(data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["transactions"] });
      qc.invalidateQueries({ queryKey: ["accounts"] });
      qc.invalidateQueries({ queryKey: ["netWorth"] });
      setShowForm(false);
      toast.success("Transaction added");
    },
    onError: () => toast.error("Failed to create transaction"),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => transactionService.delete(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["transactions"] });
      qc.invalidateQueries({ queryKey: ["accounts"] });
      toast.success("Transaction deleted");
    },
  });

  const { register, handleSubmit, watch, reset, formState: { errors, isSubmitting } } =
    useForm<FormValues>({ resolver: zodResolver(schema) });

  const txType = watch("transactionType");
  const onSubmit = (values: FormValues) => createMutation.mutate(values);

  const transactions = pageData?.content ?? [];
  const totalPages = pageData?.totalPages ?? 1;
  const currentPage = filters.page ?? 0;

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">Transactions</h1>
        <button
          onClick={() => { setShowForm(!showForm); reset(); }}
          className="flex items-center gap-2 rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90"
        >
          <Plus className="h-4 w-4" /> Add
        </button>
      </div>

      {/* Filters */}
      <div className="flex flex-wrap gap-3">
        <select
          className={inputCls + " max-w-xs"}
          onChange={(e) => setFilters((f) => ({ ...f, type: e.target.value || undefined, page: 0 }))}
        >
          <option value="">All types</option>
          {["INCOME", "EXPENSE", "TRANSFER"].map((t) => <option key={t}>{t}</option>)}
        </select>
        <input
          type="date"
          className={inputCls + " max-w-xs"}
          onChange={(e) => setFilters((f) => ({ ...f, startDate: e.target.value || undefined, page: 0 }))}
        />
        <input
          type="date"
          className={inputCls + " max-w-xs"}
          onChange={(e) => setFilters((f) => ({ ...f, endDate: e.target.value || undefined, page: 0 }))}
        />
      </div>

      {showForm && (
        <div className="rounded-xl border border-border bg-card p-5">
          <h2 className="mb-4 font-semibold">New Transaction</h2>
          <form onSubmit={handleSubmit(onSubmit)} className="grid grid-cols-2 gap-4">
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
            <Field label="Date" error={errors.transactionDate?.message}>
              <input type="date" {...register("transactionDate")} className={inputCls} />
            </Field>
            {txType === "TRANSFER" && (
              <Field label="Transfer to Account" error={errors.transferAccountId?.message}>
                <select {...register("transferAccountId")} className={inputCls}>
                  {accounts.map((a) => <option key={a.id} value={a.id}>{a.name}</option>)}
                </select>
              </Field>
            )}
            <Field label="Note" error={errors.note?.message}>
              <input {...register("note")} className={inputCls} />
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
      ) : isError ? (
        <p className="text-destructive">Failed to load transactions. Check your connection or try refreshing.</p>
      ) : (
        <>
          <div className="overflow-hidden rounded-xl border border-border bg-card">
            <table className="w-full text-sm">
              <thead className="border-b border-border bg-muted/50">
                <tr>
                  {["Date", "Account", "Type", "Category", "Amount", "Note", ""].map((h) => (
                    <th key={h} className="px-4 py-3 text-left font-medium text-muted-foreground">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {transactions.length === 0 ? (
                  <tr><td colSpan={7} className="px-4 py-8 text-center text-muted-foreground">No transactions found</td></tr>
                ) : (
                  transactions.map((tx) => (
                    <tr key={tx.id} className="hover:bg-muted/30">
                      <td className="px-4 py-3">{formatDate(tx.transactionDate)}</td>
                      <td className="px-4 py-3">{tx.accountName}</td>
                      <td className="px-4 py-3">
                        <span className={cn("rounded-full px-2 py-0.5 text-xs font-medium",
                          tx.transactionType === "INCOME" ? "bg-green-100 text-green-700" :
                          tx.transactionType === "EXPENSE" ? "bg-red-100 text-red-700" :
                          "bg-blue-100 text-blue-700"
                        )}>{tx.transactionType}</span>
                      </td>
                      <td className="px-4 py-3 text-muted-foreground">{tx.categoryName ?? "—"}</td>
                      <td className={cn("px-4 py-3 font-medium",
                        tx.transactionType === "INCOME" ? "text-green-600" : "text-foreground"
                      )}>
                        {tx.transactionType === "INCOME" ? "+" : tx.transactionType === "EXPENSE" ? "−" : ""}
                        {formatCurrency(tx.amount)}
                      </td>
                      <td className="px-4 py-3 text-muted-foreground">{tx.note ?? "—"}</td>
                      <td className="px-4 py-3">
                        <button onClick={() => deleteMutation.mutate(tx.id)} className="text-muted-foreground hover:text-destructive">
                          <Trash2 className="h-4 w-4" />
                        </button>
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>

          {/* Pagination */}
          <div className="flex items-center justify-between">
            <p className="text-sm text-muted-foreground">
              Page {currentPage + 1} of {totalPages}
            </p>
            <div className="flex gap-2">
              <button
                disabled={currentPage === 0}
                onClick={() => setFilters((f) => ({ ...f, page: currentPage - 1 }))}
                className="rounded-md border p-1.5 hover:bg-accent disabled:opacity-40"
              >
                <ChevronLeft className="h-4 w-4" />
              </button>
              <button
                disabled={currentPage >= totalPages - 1}
                onClick={() => setFilters((f) => ({ ...f, page: currentPage + 1 }))}
                className="rounded-md border p-1.5 hover:bg-accent disabled:opacity-40"
              >
                <ChevronRight className="h-4 w-4" />
              </button>
            </div>
          </div>
        </>
      )}
    </div>
  );
}

const inputCls =
  "w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring";

function Field({ label, error, children }: { label: string; error?: string; children: React.ReactNode }) {
  return (
    <div>
      <label className="mb-1 block text-sm font-medium">{label}</label>
      {children}
      {error && <p className="mt-1 text-xs text-destructive">{error}</p>}
    </div>
  );
}
