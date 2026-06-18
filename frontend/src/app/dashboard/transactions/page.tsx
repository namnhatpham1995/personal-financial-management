"use client";

import { Suspense } from "react";
import { useSearchParams, useRouter } from "next/navigation";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { transactionService, TransactionFilters, Transaction } from "@/services/transaction-service";
import type { UpdateTransactionPayload } from "@/services/transaction-service";
import { accountService } from "@/services/account-service";
import { categoryService } from "@/services/category-service";
import { formatCurrency, formatDate } from "@/lib/utils";
import { useState } from "react";
import { toast } from "sonner";
import { Plus, Trash2, Pencil, ChevronLeft, ChevronRight } from "lucide-react";
import { cn } from "@/lib/utils";
import { TransactionForm } from "./transaction-form";
import type { TransactionFormValues } from "./transaction-form";
import { RecurringTab } from "./recurring-tab";
import { Badge } from "@/components/ui/badge";

export default function TransactionsPage() {
  return (
    <Suspense fallback={<p className="text-slate-500">Loading…</p>}>
      <TransactionsContent />
    </Suspense>
  );
}

function TransactionsContent() {
  const searchParams = useSearchParams();
  const router = useRouter();
  const activeTab = searchParams.get("tab") ?? "history";

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold tracking-tight text-slate-100">Transactions</h1>
      </div>

      {/* Tab bar */}
      <div className="flex gap-1 border-b border-slate-800/60">
        {(["history", "recurring"] as const).map((tab) => (
          <button
            key={tab}
            onClick={() =>
              router.push(
                tab === "history"
                  ? "/dashboard/transactions"
                  : "/dashboard/transactions?tab=recurring",
                { scroll: false }
              )
            }
            className={cn(
              "px-4 py-2 text-sm font-medium capitalize transition-colors",
              activeTab === tab
                ? "border-b-2 border-emerald-400 text-emerald-400"
                : "text-slate-500 hover:text-slate-300"
            )}
          >
            {tab}
          </button>
        ))}
      </div>

      {activeTab === "recurring" ? <RecurringTab /> : <HistoryTab />}
    </div>
  );
}

function HistoryTab() {
  const qc = useQueryClient();
  const [showForm, setShowForm] = useState(false);
  const [editingTx, setEditingTx] = useState<Transaction | null>(null);
  const [filters, setFilters] = useState<TransactionFilters>({ page: 0, size: 20 });

  const { data: pageData, isLoading, isError } = useQuery({
    queryKey: ["transactions", filters],
    queryFn: () => transactionService.list(filters),
  });

  const { data: accounts = [] } = useQuery({
    queryKey: ["accounts"],
    queryFn: accountService.list,
  });

  const { data: categories = [] } = useQuery({
    queryKey: ["categories"],
    queryFn: categoryService.list,
  });

  const invalidateAfterMutation = () => {
    qc.invalidateQueries({ queryKey: ["transactions"] });
    qc.invalidateQueries({ queryKey: ["accounts"] });
    qc.invalidateQueries({ queryKey: ["netWorth"] });
  };

  const createMutation = useMutation({
    mutationFn: (data: TransactionFormValues) => transactionService.create(data),
    onSuccess: () => { invalidateAfterMutation(); closeForm(); toast.success("Transaction added"); },
    onError: () => toast.error("Failed to create transaction"),
  });

  const updateMutation = useMutation({
    mutationFn: ({ id, data }: { id: number; data: UpdateTransactionPayload }) =>
      transactionService.update(id, data),
    onSuccess: () => { invalidateAfterMutation(); closeForm(); toast.success("Transaction updated"); },
    onError: () => toast.error("Failed to update transaction"),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => transactionService.delete(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["transactions"] });
      qc.invalidateQueries({ queryKey: ["accounts"] });
      toast.success("Transaction deleted");
    },
  });

  const closeForm = () => { setShowForm(false); setEditingTx(null); };
  const startEdit = (tx: Transaction) => { setEditingTx(tx); setShowForm(true); };
  const handleAddClick = () => {
    if (editingTx) { setEditingTx(null); } else { setShowForm((prev) => !prev); }
  };

  const onSubmit = (values: TransactionFormValues) => {
    if (editingTx) {
      updateMutation.mutate({ id: editingTx.id, data: { amount: values.amount, transactionDate: values.transactionDate, categoryId: values.categoryId, note: values.note } });
    } else {
      createMutation.mutate(values);
    }
  };

  const transactions = pageData?.content ?? [];
  const totalPages = pageData?.totalPages ?? 1;
  const currentPage = filters.page ?? 0;
  const isMutating = createMutation.isPending || updateMutation.isPending;

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center gap-3">
        {/* Filters */}
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
        <div className="ml-auto">
          <button
            onClick={handleAddClick}
            className="flex items-center gap-2 rounded-lg bg-emerald-500/10 border border-emerald-500/20 px-4 py-2 text-sm font-medium text-emerald-400 hover:bg-emerald-500/20 transition-colors"
          >
            <Plus className="h-4 w-4" /> Add
          </button>
        </div>
      </div>

      {showForm && (
        <TransactionForm
          editingTx={editingTx}
          accounts={accounts}
          categories={categories}
          isPending={isMutating}
          onCancel={closeForm}
          onSubmit={onSubmit}
        />
      )}

      {isLoading ? (
        <p className="text-slate-500">Loading…</p>
      ) : isError ? (
        <p className="text-rose-400">Failed to load transactions. Check your connection or try refreshing.</p>
      ) : (
        <>
          <div className="overflow-x-auto rounded-xl border border-slate-800/60 bg-slate-900/40 backdrop-blur-sm">
            <table className="w-full text-sm">
              <thead className="border-b border-slate-800/60">
                <tr>
                  {["Date", "Account", "Type", "Category", "Amount", "Note", ""].map((h) => (
                    <th key={h} className="px-4 py-3 text-left text-xs font-medium uppercase tracking-wide text-slate-500">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-800/40">
                {transactions.length === 0 ? (
                  <tr>
                    <td colSpan={7} className="px-4 py-8 text-center text-slate-500">
                      No transactions found
                    </td>
                  </tr>
                ) : (
                  transactions.map((tx) => (
                    <tr key={tx.id} className="transition-colors hover:bg-slate-800/30">
                      <td className="px-4 py-3 font-mono tabular-nums text-xs text-slate-400">{formatDate(tx.transactionDate)}</td>
                      <td className="px-4 py-3 text-slate-300">{tx.accountName}</td>
                      <td className="px-4 py-3">
                        <Badge variant={tx.transactionType === "INCOME" ? "income" : tx.transactionType === "EXPENSE" ? "expense" : "transfer"}>
                          {tx.transactionType}
                        </Badge>
                      </td>
                      <td className="px-4 py-3 text-slate-500">{tx.categoryName ?? "—"}</td>
                      <td className={cn(
                        "px-4 py-3 font-mono tabular-nums font-medium",
                        tx.transactionType === "INCOME" ? "text-emerald-400" :
                        tx.transactionType === "EXPENSE" ? "text-rose-400" : "text-slate-400"
                      )}>
                        {tx.transactionType === "INCOME" ? "+" : tx.transactionType === "EXPENSE" ? "−" : ""}
                        {formatCurrency(tx.amount, tx.currency)}
                      </td>
                      <td className="px-4 py-3 text-slate-500">{tx.note ?? "—"}</td>
                      <td className="px-4 py-3">
                        <div className="flex items-center gap-2">
                          <button
                            onClick={() => startEdit(tx)}
                            title="Edit transaction"
                            className="text-slate-500 hover:text-slate-200 transition-colors"
                          >
                            <Pencil className="h-4 w-4" />
                          </button>
                          <button
                            onClick={() => deleteMutation.mutate(tx.id)}
                            title="Delete transaction"
                            className="text-slate-500 hover:text-rose-400 transition-colors"
                          >
                            <Trash2 className="h-4 w-4" />
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>

          {/* Pagination */}
          <div className="flex items-center justify-between">
            <p className="font-mono tabular-nums text-xs text-slate-500">
              Page {currentPage + 1} of {totalPages}
            </p>
            <div className="flex gap-2">
              <button
                disabled={currentPage === 0}
                onClick={() => setFilters((f) => ({ ...f, page: currentPage - 1 }))}
                className="rounded-lg border border-slate-800/60 p-1.5 text-slate-400 hover:bg-slate-800/60 hover:text-slate-100 disabled:opacity-40 transition-colors"
              >
                <ChevronLeft className="h-4 w-4" />
              </button>
              <button
                disabled={currentPage >= totalPages - 1}
                onClick={() => setFilters((f) => ({ ...f, page: currentPage + 1 }))}
                className="rounded-lg border border-slate-800/60 p-1.5 text-slate-400 hover:bg-slate-800/60 hover:text-slate-100 disabled:opacity-40 transition-colors"
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
  "w-full rounded-lg border border-slate-800/60 bg-slate-900/40 px-3 py-2 text-sm text-slate-200 placeholder:text-slate-600 focus:outline-none focus:ring-2 focus:ring-emerald-500/40 focus:border-emerald-500/40 transition-colors";
