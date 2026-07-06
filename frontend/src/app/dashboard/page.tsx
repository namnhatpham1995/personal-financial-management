"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState, useMemo, useEffect } from "react";
import { format, subMonths, startOfMonth, endOfMonth } from "date-fns";
import { toast } from "sonner";
import { analyticsService } from "@/services/analytics-service";
import {
  accountService,
  Account,
  CreateAccountPayload,
  UpdateAccountPayload,
} from "@/services/account-service";
import {
  DeleteAccountDialog,
  EditAccountDialog,
} from "@/components/accounts/account-management-ui";
import { BalanceBreakdown } from "@/components/accounts/balance-breakdown";
import { AccountDetailDialog } from "@/components/accounts/account-detail-dialog";
import { FeaturedBalanceCard } from "@/components/accounts/featured-balance-card";
import { CurrencySection } from "@/components/overview/currency-section";

const RANGE_OPTIONS = [
  { label: "1M", months: 1 },
  { label: "3M", months: 3 },
  { label: "6M", months: 6 },
  { label: "1Y", months: 12 },
] as const;

const MAIN_CURRENCY_STORAGE_KEY = "fintrack.mainCurrency";

function readStoredMainCurrency(): string | null {
  if (typeof window === "undefined") return null;
  try {
    return window.localStorage.getItem(MAIN_CURRENCY_STORAGE_KEY);
  } catch {
    return null;
  }
}

export default function DashboardPage() {
  const qc = useQueryClient();
  const [months, setMonths] = useState(6);
  const [mainCurrencyOverride, setMainCurrencyOverride] = useState<string | null>(() =>
    readStoredMainCurrency()
  );
  const [showCreateForm, setShowCreateForm] = useState(false);
  const [editingAccount, setEditingAccount] = useState<Account | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<Account | null>(null);
  const [detailAccount, setDetailAccount] = useState<Account | null>(null);

  const from = format(startOfMonth(subMonths(new Date(), months - 1)), "yyyy-MM-dd");
  const to = format(endOfMonth(new Date()), "yyyy-MM-dd");

  const { data: balancesByCurrency = [] } = useQuery({
    queryKey: ["balances"],
    queryFn: analyticsService.balances,
  });
  const { data: accounts = [] } = useQuery({
    queryKey: ["accounts"],
    queryFn: accountService.list,
  });
  const { data: deletePreview } = useQuery({
    queryKey: ["deletePreview", deleteTarget?.id],
    queryFn: () => accountService.deletePreview(deleteTarget!.id),
    enabled: deleteTarget !== null,
  });
  const { data: trend = [] } = useQuery({
    queryKey: ["trend", from, to],
    queryFn: () => analyticsService.incomeVsExpense(from, to),
  });
  const { data: spending = [] } = useQuery({
    queryKey: ["spending", from, to],
    queryFn: () => analyticsService.spendingByCategory(from, to),
  });
  const { data: budgetProgress = [] } = useQuery({
    queryKey: ["budgetProgress"],
    queryFn: analyticsService.budgetProgress,
  });

  // Derive available target currencies from balance buckets
  const availableCurrencies = useMemo(
    () => balancesByCurrency.map((b) => b.currency),
    [balancesByCurrency]
  );

  // Main currency for the featured balance card's converted grand total.
  // Falls back to the first held currency when unset or no longer held.
  const mainCurrency =
    mainCurrencyOverride && availableCurrencies.includes(mainCurrencyOverride)
      ? mainCurrencyOverride
      : (availableCurrencies[0] ?? null);

  useEffect(() => {
    if (!mainCurrency) return;
    try {
      window.localStorage.setItem(MAIN_CURRENCY_STORAGE_KEY, mainCurrency);
    } catch {
      // localStorage unavailable (e.g. private browsing) — persistence is best-effort
    }
  }, [mainCurrency]);

  const { data: balancesSummary, isLoading: balancesSummaryLoading } = useQuery({
    queryKey: ["balancesSummary", mainCurrency],
    queryFn: () => analyticsService.balancesSummary(mainCurrency!),
    enabled: !!mainCurrency && availableCurrencies.length > 1,
  });

  // Section list: every currency with a balance, transaction, or budget footprint.
  const currencies = Array.from(
    new Set([
      ...balancesByCurrency.map((b) => b.currency),
      ...trend.map((t) => t.currency),
      ...spending.map((s) => s.currency),
      ...budgetProgress.map((b) => b.currency),
    ])
  ).sort();

  const bucketsByCurrency = new Map(balancesByCurrency.map((bucket) => [bucket.currency, bucket]));

  const invalidateAccountViews = () => {
    qc.invalidateQueries({ queryKey: ["accounts"] });
    qc.invalidateQueries({ queryKey: ["balances"] });
    qc.invalidateQueries({ queryKey: ["balancesSummary"] });
    qc.invalidateQueries({ queryKey: ["transactions"] });
    qc.invalidateQueries({ queryKey: ["recentTransactions"] });
    qc.invalidateQueries({ queryKey: ["spending"] });
    qc.invalidateQueries({ queryKey: ["trend"] });
  };

  const createMutation = useMutation({
    mutationFn: (data: CreateAccountPayload) => accountService.create(data),
    onSuccess: () => {
      invalidateAccountViews();
      setShowCreateForm(false);
      toast.success("Account created");
    },
    onError: () => toast.error("Failed to create account"),
  });

  const editMutation = useMutation({
    mutationFn: ({ id, data }: { id: number; data: UpdateAccountPayload }) =>
      accountService.update(id, data),
    onSuccess: () => {
      invalidateAccountViews();
      setEditingAccount(null);
      toast.success("Account updated");
    },
    onError: () => toast.error("Failed to update account"),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => accountService.delete(id),
    onSuccess: () => {
      invalidateAccountViews();
      setDeleteTarget(null);
      toast.success("Account deleted");
    },
    onError: () => toast.error("Failed to delete account"),
  });

  return (
    <div className="space-y-6">
      {/* Header: title + range selector */}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-2xl font-bold tracking-tight text-foreground">Overview</h1>

        <div className="flex rounded-full border border-border bg-card p-0.5">
          {RANGE_OPTIONS.map(({ label, months: m }) => (
            <button
              key={label}
              onClick={() => setMonths(m)}
              className={`rounded-full px-3 py-1 text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/40 ${
                months === m
                  ? "bg-primary/10 text-primary border border-primary/20"
                  : "text-muted-foreground hover:text-foreground"
              }`}
            >
              {label}
            </button>
          ))}
        </div>
      </div>

      {balancesByCurrency.length > 0 && (
        <FeaturedBalanceCard
          balances={balancesByCurrency}
          mainCurrency={mainCurrency}
          onMainCurrencyChange={setMainCurrencyOverride}
          summary={balancesSummary ?? null}
          isSummaryLoading={balancesSummaryLoading}
        />
      )}

      <BalanceBreakdown
        hasAccounts={accounts.length > 0}
        showCreateForm={showCreateForm}
        isCreating={createMutation.isPending}
        onAdd={() => {
          setShowCreateForm(true);
          setEditingAccount(null);
        }}
        onCreate={(values) => createMutation.mutate(values)}
        onCancelCreate={() => setShowCreateForm(false)}
      />

      {currencies.length === 0 ? (
        <p className="text-sm text-muted-foreground">No transaction data for this period.</p>
      ) : (
        currencies.map((currency) => (
          <CurrencySection
            key={currency}
            currency={currency}
            nativeTotal={bucketsByCurrency.get(currency)?.totalBalance}
            trend={trend.filter((t) => t.currency === currency)}
            spending={spending.filter((s) => s.currency === currency)}
            accounts={accounts.filter((account) => account.currency === currency)}
            onEditAccount={(account) => {
              setEditingAccount(account);
              setShowCreateForm(false);
            }}
            onDeleteAccount={setDeleteTarget}
            onOpenAccountDetail={setDetailAccount}
          />
        ))
      )}

      {detailAccount && (
        <AccountDetailDialog
          account={detailAccount}
          onClose={() => setDetailAccount(null)}
        />
      )}

      {editingAccount && (
        <EditAccountDialog
          account={editingAccount}
          onSubmit={(values) => editMutation.mutate({ id: editingAccount.id, data: values })}
          onCancel={() => setEditingAccount(null)}
          isPending={editMutation.isPending}
        />
      )}

      {deleteTarget && (
        <DeleteAccountDialog
          account={deleteTarget}
          transactionCount={deletePreview?.transactionCount ?? null}
          onConfirm={() => deleteMutation.mutate(deleteTarget.id)}
          onCancel={() => setDeleteTarget(null)}
          isPending={deleteMutation.isPending}
        />
      )}
    </div>
  );
}
