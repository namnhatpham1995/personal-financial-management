"use client";

import { Suspense } from "react";
import { useSearchParams, useRouter } from "next/navigation";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { transactionService, TransactionFilters, Transaction } from "@/services/transaction-service";
import type { UpdateTransactionPayload } from "@/services/transaction-service";
import { accountService } from "@/services/account-service";
import { categoryService } from "@/services/category-service";
import { useState } from "react";
import { toast } from "sonner";
import { Plus } from "lucide-react";
import { cn } from "@/lib/utils";
import { TransactionForm } from "./transaction-form";
import type { TransactionFormValues } from "./transaction-form";
import { RecurringTab } from "./recurring-tab";
import { TransactionTable } from "@/components/transactions/transaction-table";
import { Button } from "@/components/ui/button";

const inputCls =
  "w-full rounded-md border border-border bg-card px-3.5 py-2.5 text-base text-foreground placeholder:text-muted-foreground/50 focus:outline-none focus:ring-2 focus:ring-primary/40 focus:border-primary/40 transition-colors";

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
  const t = useTranslations("transactions");

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold tracking-tight text-foreground">{t("title")}</h1>
      </div>

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
              "px-4 py-2 text-sm font-medium transition-colors",
              activeTab === tab
                ? "border-b-2 border-primary text-primary"
                : "text-muted-foreground hover:text-foreground"
            )}
          >
            {t(`tabs.${tab}`)}
          </button>
        ))}
      </div>

      {activeTab === "recurring" ? <RecurringTab /> : <HistoryTab />}
    </div>
  );
}

function HistoryTab() {
  const qc = useQueryClient();
  const t = useTranslations("transactions");
  const tCommon = useTranslations("common");
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
    qc.invalidateQueries({ queryKey: ["balances"] });
  };

  const createMutation = useMutation({
    mutationFn: (data: TransactionFormValues) => transactionService.create(data),
    onSuccess: () => { invalidateAfterMutation(); closeForm(); toast.success(t("toast.added")); },
    onError: () => toast.error(t("toast.createFailed")),
  });

  const updateMutation = useMutation({
    mutationFn: ({ id, data }: { id: number; data: UpdateTransactionPayload }) =>
      transactionService.update(id, data),
    onSuccess: () => { invalidateAfterMutation(); closeForm(); toast.success(t("toast.updated")); },
    onError: () => toast.error(t("toast.updateFailed")),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => transactionService.delete(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["transactions"] });
      qc.invalidateQueries({ queryKey: ["accounts"] });
      toast.success(t("toast.deleted"));
    },
    onError: () => toast.error(t("toast.deleteFailed")),
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
          destinationAmount: values.destinationAmount,
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
      <div className="flex flex-wrap items-center gap-3">
        <select
          className={inputCls + " max-w-xs"}
          onChange={(e) => setFilters((f) => ({ ...f, type: e.target.value || undefined, page: 0 }))}
        >
          <option value="">{t("allTypes")}</option>
          {["INCOME", "EXPENSE", "TRANSFER"].map((type) => <option key={type}>{type}</option>)}
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
          <Button onClick={handleAddClick}>
            <Plus className="h-4 w-4" /> {t("add")}
          </Button>
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
        <p className="text-muted-foreground">{tCommon("loading")}</p>
      ) : isError ? (
        <p className="text-destructive">{t("loadError")}</p>
      ) : (
        <TransactionTable
          transactions={transactions}
          onEdit={startEdit}
          onDelete={(tx) => deleteMutation.mutate(tx.id)}
          deletingId={deleteMutation.isPending ? deleteMutation.variables ?? null : null}
          pagination={{
            currentPage,
            totalPages,
            onPrev: () => setFilters((f) => ({ ...f, page: currentPage - 1 })),
            onNext: () => setFilters((f) => ({ ...f, page: currentPage + 1 })),
          }}
        />
      )}
    </div>
  );
}
