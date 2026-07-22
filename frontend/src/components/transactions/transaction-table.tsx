"use client";

import { ChevronLeft, ChevronRight, Pencil, Trash2 } from "lucide-react";
import { useLocale, useTranslations } from "next-intl";
import type { Transaction } from "@/services/transaction-service";
import { formatDate } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import { MoneyText } from "@/components/ui/money-text";
import { useTransactionTypeLabel } from "@/lib/enum-labels";
import { useCategoryLabel } from "@/lib/category-label";

export interface TransactionTablePagination {
  currentPage: number;
  totalPages: number;
  onPrev: () => void;
  onNext: () => void;
}

interface TransactionTableProps {
  transactions: Transaction[];
  /** Show the Account column. Hidden in single-account views where it is redundant. */
  showAccountColumn?: boolean;
  /** Row edit action. Omit (with onDelete) to render a read-only table without an actions column. */
  onEdit?: (tx: Transaction) => void;
  /** Row delete action. */
  onDelete?: (tx: Transaction) => void;
  /** Id of the transaction whose delete is in flight (disables its delete button). */
  deletingId?: number | null;
  /** Message shown when there are no transactions. */
  emptyMessage?: string;
  /** Optional pagination footer. */
  pagination?: TransactionTablePagination;
}

/**
 * Presentational transaction table shared by the Transactions history tab and the
 * account detail view. Owns no data fetching — callers pass rows and handlers.
 */
export function TransactionTable({
  transactions,
  showAccountColumn = true,
  onEdit,
  onDelete,
  deletingId,
  emptyMessage,
  pagination,
}: TransactionTableProps) {
  const t = useTranslations("transactions.table");
  const locale = useLocale();
  const getTypeLabel = useTransactionTypeLabel();
  const getCategoryLabel = useCategoryLabel();
  const showActions = Boolean(onEdit || onDelete);
  const headers = [
    t("dateHeader"),
    ...(showAccountColumn ? [t("accountHeader")] : []),
    t("typeHeader"),
    t("categoryHeader"),
    t("amountHeader"),
    t("noteHeader"),
    ...(showActions ? [""] : []),
  ];

  return (
    <div className="space-y-6">
      <div className="overflow-x-auto rounded-lg border border-border bg-card">
        <table className="w-full text-sm">
          <thead className="border-b border-border">
            <tr>
              {headers.map((h, i) => (
                <th
                  key={h || `col-${i}`}
                  className="px-4 py-3 text-left text-xs font-medium uppercase tracking-wide text-muted-foreground"
                >
                  {h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {transactions.length === 0 ? (
              <tr>
                <td colSpan={headers.length} className="px-4 py-8 text-center text-muted-foreground">
                  {emptyMessage ?? t("emptyMessage")}
                </td>
              </tr>
            ) : (
              transactions.map((tx) => (
                <tr key={tx.id} className="transition-colors hover:bg-hover-surface">
                  <td className="px-4 py-3 font-mono tabular-nums text-xs text-muted-foreground">
                    {formatDate(tx.transactionDate, locale)}
                  </td>
                  {showAccountColumn && (
                    <td className="px-4 py-3 text-foreground">
                      <span className="block max-w-[12rem] truncate" title={tx.accountName}>
                        {tx.accountName}
                      </span>
                    </td>
                  )}
                  <td className="px-4 py-3">
                    <Badge
                      variant={
                        tx.transactionType === "INCOME"
                          ? "income"
                          : tx.transactionType === "EXPENSE"
                          ? "expense"
                          : "transfer"
                      }
                    >
                      {getTypeLabel(tx.transactionType)}
                    </Badge>
                  </td>
                  <td className="px-4 py-3 text-muted-foreground">
                    {getCategoryLabel({ name: tx.categoryName, categoryId: tx.categoryId }) ?? "—"}
                  </td>
                  <td className="px-4 py-3">
                    {tx.transactionType === "TRANSFER" && tx.destinationAmount && tx.destinationCurrency ? (
                      <span className="flex items-center gap-1.5 font-mono tabular-nums font-medium text-muted-foreground">
                        <MoneyText amount={Number(tx.amount)} type="TRANSFER" currency={tx.currency} />
                        <span aria-hidden="true">→</span>
                        <MoneyText amount={Number(tx.destinationAmount)} type="TRANSFER" currency={tx.destinationCurrency} />
                      </span>
                    ) : (
                      <MoneyText
                        amount={Number(tx.amount)}
                        type={tx.transactionType}
                        currency={tx.currency}
                        className="font-medium"
                      />
                    )}
                  </td>
                  <td className="px-4 py-3 text-muted-foreground">
                    <span className="block max-w-[16rem] truncate" title={tx.note ?? undefined}>
                      {tx.note ?? "—"}
                    </span>
                  </td>
                  {showActions && (
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-2">
                        {onEdit && (
                          <button
                            onClick={() => onEdit(tx)}
                            title={t("editAria", { date: formatDate(tx.transactionDate, locale) })}
                            aria-label={t("editAria", { date: formatDate(tx.transactionDate, locale) })}
                            className="inline-flex min-h-10 min-w-10 items-center justify-center rounded text-muted-foreground hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/40 transition-colors"
                          >
                            <Pencil className="h-4 w-4" />
                          </button>
                        )}
                        {onDelete && (
                          <button
                            onClick={() => onDelete(tx)}
                            disabled={deletingId === tx.id}
                            title={t("deleteAria", { date: formatDate(tx.transactionDate, locale) })}
                            aria-label={t("deleteAria", { date: formatDate(tx.transactionDate, locale) })}
                            className="inline-flex min-h-10 min-w-10 items-center justify-center rounded text-muted-foreground hover:text-destructive focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/40 disabled:opacity-40 transition-colors"
                          >
                            <Trash2 className="h-4 w-4" />
                          </button>
                        )}
                      </div>
                    </td>
                  )}
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {pagination && (
        <div className="flex items-center justify-between">
          <p className="font-mono tabular-nums text-xs text-muted-foreground">
            {t("pageOf", { current: pagination.currentPage + 1, total: pagination.totalPages })}
          </p>
          <div className="flex gap-2">
            <button
              disabled={pagination.currentPage === 0}
              onClick={pagination.onPrev}
              className="inline-flex min-h-11 min-w-11 items-center justify-center rounded-sm border border-border text-muted-foreground hover:bg-secondary hover:text-foreground disabled:opacity-40 transition-colors"
            >
              <ChevronLeft className="h-4 w-4" />
            </button>
            <button
              disabled={pagination.currentPage >= pagination.totalPages - 1}
              onClick={pagination.onNext}
              className="inline-flex min-h-11 min-w-11 items-center justify-center rounded-sm border border-border text-muted-foreground hover:bg-secondary hover:text-foreground disabled:opacity-40 transition-colors"
            >
              <ChevronRight className="h-4 w-4" />
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
