"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { format, subMonths, startOfMonth, endOfMonth } from "date-fns";
import { X } from "lucide-react";
import type { Account } from "@/services/account-service";
import { transactionService } from "@/services/transaction-service";
import { analyticsService } from "@/services/analytics-service";
import { formatCurrency } from "@/lib/utils";
import { SpendingDonutChart } from "@/components/charts/spending-donut-chart";
import { TransactionTable } from "@/components/transactions/transaction-table";
import { Card } from "@/components/ui/card";
import { formatAccountType } from "@/components/accounts/account-management-ui";

const PAGE_SIZE = 10;

const inputCls =
  "rounded-lg border border-border bg-card px-3 py-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-primary/40 focus:border-primary/40 transition-colors";

/**
 * Read-only drill-down for a single account: its own transactions, a separate
 * incoming-transfers list, and an expense-by-category pie with a distinct hatched
 * "incoming" slice. One date range scopes all three. Mounted only while open, so
 * its queries are lazy by construction.
 */
export function AccountDetailDialog({ account, onClose }: { account: Account; onClose: () => void }) {
  // Default to the last 12 months; the pie endpoint requires a concrete range, so
  // all three sections share this window rather than an open-ended "all time".
  const [startDate, setStartDate] = useState(
    format(startOfMonth(subMonths(new Date(), 11)), "yyyy-MM-dd")
  );
  const [endDate, setEndDate] = useState(format(endOfMonth(new Date()), "yyyy-MM-dd"));
  const [ownPage, setOwnPage] = useState(0);
  const [incomingPage, setIncomingPage] = useState(0);

  const { data: ownData, isLoading: ownLoading } = useQuery({
    queryKey: ["accountDetail", "own", account.id, startDate, endDate, ownPage],
    queryFn: () =>
      transactionService.list({
        accountId: account.id,
        startDate,
        endDate,
        page: ownPage,
        size: PAGE_SIZE,
      }),
  });

  const { data: incomingData, isLoading: incomingLoading } = useQuery({
    queryKey: ["accountDetail", "incoming", account.id, startDate, endDate, incomingPage],
    queryFn: () =>
      transactionService.list({
        transferAccountId: account.id,
        startDate,
        endDate,
        page: incomingPage,
        size: PAGE_SIZE,
      }),
  });

  const { data: spending = [] } = useQuery({
    queryKey: ["accountDetail", "spending", account.id, startDate, endDate],
    queryFn: () => analyticsService.spendingByCategory(startDate, endDate, account.id),
  });

  const { data: incomingTotal } = useQuery({
    queryKey: ["accountDetail", "incomingTotal", account.id, startDate, endDate],
    queryFn: () => analyticsService.incomingTransferTotal(account.id, startDate, endDate),
  });

  const incomingAmount = incomingTotal ? Number(incomingTotal.total) : 0;

  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-black/60 p-4 backdrop-blur-sm">
      <div className="my-8 w-full max-w-4xl rounded-lg border border-border bg-card p-6 shadow-card">
        {/* Header */}
        <div className="flex items-start justify-between gap-4">
          <div className="min-w-0">
            <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
              {formatAccountType(account.accountType)}
            </p>
            <h2 className="mt-1 truncate text-xl font-bold tracking-tight text-foreground" title={account.name}>
              {account.name}
            </h2>
            <p className="mt-1 font-mono text-2xl font-bold tabular-nums text-foreground">
              {formatCurrency(account.currentBalance, account.currency)}
            </p>
          </div>
          <button
            onClick={onClose}
            className="rounded-lg p-1.5 text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/40"
            title="Close"
            aria-label="Close account detail"
          >
            <X className="h-5 w-5" />
          </button>
        </div>

        {/* Date range — scopes all three sections */}
        <div className="mt-4 flex flex-wrap items-center gap-3">
          <label className="flex items-center gap-2 text-xs text-muted-foreground">
            From
            <input
              type="date"
              value={startDate}
              max={endDate}
              onChange={(e) => {
                setStartDate(e.target.value);
                setOwnPage(0);
                setIncomingPage(0);
              }}
              className={inputCls}
            />
          </label>
          <label className="flex items-center gap-2 text-xs text-muted-foreground">
            To
            <input
              type="date"
              value={endDate}
              min={startDate}
              onChange={(e) => {
                setEndDate(e.target.value);
                setOwnPage(0);
                setIncomingPage(0);
              }}
              className={inputCls}
            />
          </label>
        </div>

        <div className="mt-6 grid grid-cols-1 gap-6 lg:grid-cols-[320px_1fr]">
          {/* Pie: expense categories + hatched incoming slice */}
          <Card className="p-5">
            <h3 className="mb-4 font-semibold tracking-tight text-foreground">Expense by category</h3>
            <SpendingDonutChart
              data={spending}
              currency={account.currency}
              incoming={incomingAmount > 0 ? { amount: incomingAmount } : undefined}
            />
          </Card>

          {/* Lists */}
          <div className="space-y-6">
            <section>
              <h3 className="mb-3 font-semibold tracking-tight text-foreground">Transactions</h3>
              {ownLoading ? (
                <p className="text-sm text-muted-foreground">Loading…</p>
              ) : (
                <TransactionTable
                  transactions={ownData?.content ?? []}
                  showAccountColumn={false}
                  emptyMessage="No transactions for this account in this period"
                  pagination={{
                    currentPage: ownPage,
                    totalPages: ownData?.totalPages ?? 1,
                    onPrev: () => setOwnPage((p) => Math.max(0, p - 1)),
                    onNext: () => setOwnPage((p) => p + 1),
                  }}
                />
              )}
            </section>

            <section>
              <h3 className="mb-3 font-semibold tracking-tight text-foreground">Incoming transfers</h3>
              {incomingLoading ? (
                <p className="text-sm text-muted-foreground">Loading…</p>
              ) : (
                <TransactionTable
                  transactions={incomingData?.content ?? []}
                  showAccountColumn
                  emptyMessage="No incoming transfers in this period"
                  pagination={{
                    currentPage: incomingPage,
                    totalPages: incomingData?.totalPages ?? 1,
                    onPrev: () => setIncomingPage((p) => Math.max(0, p - 1)),
                    onNext: () => setIncomingPage((p) => p + 1),
                  }}
                />
              )}
            </section>
          </div>
        </div>
      </div>
    </div>
  );
}
