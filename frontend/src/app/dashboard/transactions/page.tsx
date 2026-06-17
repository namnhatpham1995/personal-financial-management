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

export default function TransactionsPage() {
  return (
    <Suspense fallback={<p className="text-muted-foreground">Loading…</p>}>
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
        <h1 className="text-2xl font-bold">Transactions</h1>
        {activeTab === "history" && <AddTransactionButton />}
        {activeTab === "recurring" && null}
      </div>

      {/* Tab bar */}
      <div className="flex gap-1 border-b border-border">
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
                ? "border-b-2 border-primary text-foreground"
                : "text-muted-foreground hover:text-foreground"
            )}
          >
            {tab}
          </button>
        ))}
      </div>

      {activeTab === "recurring" ? (
        <RecurringTab />
      ) : (
        <HistoryTab />
      )}
    </div>
  );
}

function AddTransactionButton() {
  // Rendered separately so HistoryTab can wire it to its own state via context —
  // for now the button lives inside HistoryTab and this slot is intentionally empty.
  return null;
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
      updateMutation.mutate({
        id: editingTx.id,
        data: {
          amount: values.amount,
          transactionDate: values.transactionDate,
          categoryId: values.categoryId,
          note: values.note,
        },
      });
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
      <div className="flex justify-end">
        <button
          onClick={handleAddClick}
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
        <p className="text-muted-foreground">Loading…</p>
      ) : isError ? (
        <p className="text-destructive">Failed to load transactions. Check your connection or try refreshing.</p>
      ) : (
        <>
          <div className="overflow-x-auto rounded-xl border border-border bg-card">
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
                  <tr>
                    <td colSpan={7} className="px-4 py-8 text-center text-muted-foreground">
                      No transactions found
                    </td>
                  </tr>
                ) : (
                  transactions.map((tx) => (
                    <tr key={tx.id} className="hover:bg-muted/30">
                      <td className="px-4 py-3">{formatDate(tx.transactionDate)}</td>
                      <td className="px-4 py-3">{tx.accountName}</td>
                      <td className="px-4 py-3">
                        <span className={cn(
                          "rounded-full px-2 py-0.5 text-xs font-medium",
                          tx.transactionType === "INCOME" ? "bg-green-100 text-green-700" :
                          tx.transactionType === "EXPENSE" ? "bg-red-100 text-red-700" :
                          "bg-blue-100 text-blue-700"
                        )}>
                          {tx.transactionType}
                        </span>
                      </td>
                      <td className="px-4 py-3 text-muted-foreground">{tx.categoryName ?? "—"}</td>
                      <td className={cn(
                        "px-4 py-3 font-medium",
                        tx.transactionType === "INCOME" ? "text-green-600" : "text-foreground"
                      )}>
                        {tx.transactionType === "INCOME" ? "+" : tx.transactionType === "EXPENSE" ? "−" : ""}
                        {formatCurrency(tx.amount, tx.currency)}
                      </td>
                      <td className="px-4 py-3 text-muted-foreground">{tx.note ?? "—"}</td>
                      <td className="px-4 py-3">
                        <div className="flex items-center gap-2">
                          <button
                            onClick={() => startEdit(tx)}
                            title="Edit transaction"
                            className="text-muted-foreground hover:text-foreground"
                          >
                            <Pencil className="h-4 w-4" />
                          </button>
                          <button
                            onClick={() => deleteMutation.mutate(tx.id)}
                            title="Delete transaction"
                            className="text-muted-foreground hover:text-destructive"
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
