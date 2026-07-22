"use client";

import { useQuery } from "@tanstack/react-query";
import { useLocale, useTranslations } from "next-intl";
import { transactionService, type Transaction } from "@/services/transaction-service";
import { MoneyText } from "@/components/ui/money-text";
import { formatDate } from "@/lib/utils";
import { useCategoryLabel } from "@/lib/category-label";

const RECENT_COUNT = 5;

interface RecentTransactionsListProps {
  currency: string;
}

/** Compact recent expense/income lists for one currency section on Overview. */
export function RecentTransactionsList({ currency }: RecentTransactionsListProps) {
  const t = useTranslations("accounts");
  const tCommon = useTranslations("common");
  const { data: expensePage, isLoading: expensesLoading } = useQuery({
    queryKey: ["recentTransactions", currency, "EXPENSE"],
    queryFn: () =>
      transactionService.list({
        currency,
        type: "EXPENSE",
        size: RECENT_COUNT,
        sortBy: "transactionDate",
        sortDir: "desc",
      }),
  });
  const { data: incomePage, isLoading: incomeLoading } = useQuery({
    queryKey: ["recentTransactions", currency, "INCOME"],
    queryFn: () =>
      transactionService.list({
        currency,
        type: "INCOME",
        size: RECENT_COUNT,
        sortBy: "transactionDate",
        sortDir: "desc",
      }),
  });

  if (expensesLoading || incomeLoading) {
    return <p className="py-8 text-center text-sm text-muted-foreground animate-pulse">{tCommon("loading")}</p>;
  }

  const expenses = expensePage?.content ?? [];
  const incomes = incomePage?.content ?? [];

  if (expenses.length === 0 && incomes.length === 0) {
    return (
      <p className="py-8 text-center text-sm text-muted-foreground">{t("noRecentTransactions")}</p>
    );
  }

  return (
    <div className="space-y-4">
      {expenses.length > 0 && (
        <div>
          <h3 className="mb-1.5 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
            {t("recentExpenses")}
          </h3>
          <ul className="space-y-1.5">
            {expenses.map((tx) => (
              <TransactionRow key={tx.id} transaction={tx} />
            ))}
          </ul>
        </div>
      )}
      {incomes.length > 0 && (
        <div>
          <h3 className="mb-1.5 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
            {t("recentIncome")}
          </h3>
          <ul className="space-y-1.5">
            {incomes.map((tx) => (
              <TransactionRow key={tx.id} transaction={tx} />
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

function TransactionRow({ transaction }: { transaction: Transaction }) {
  const t = useTranslations("accounts");
  const locale = useLocale();
  const getCategoryLabel = useCategoryLabel();
  const categoryLabel = getCategoryLabel({ name: transaction.categoryName, categoryId: transaction.categoryId });
  return (
    <li className="flex items-center justify-between gap-3 text-sm">
      <div className="min-w-0">
        <p className="truncate text-foreground" title={categoryLabel ?? transaction.note ?? undefined}>
          {categoryLabel ?? transaction.note ?? t("uncategorized")}
        </p>
        <p className="text-xs text-muted-foreground">{formatDate(transaction.transactionDate, locale)}</p>
      </div>
      <MoneyText
        amount={Number(transaction.amount)}
        type={transaction.transactionType === "TRANSFER" ? undefined : transaction.transactionType}
        signed
        className="shrink-0"
      />
    </li>
  );
}
