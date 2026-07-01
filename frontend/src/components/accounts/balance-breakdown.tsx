"use client";

import { Pencil, Plus, Trash2 } from "lucide-react";
import type { Account, CreateAccountPayload } from "@/services/account-service";
import type { CurrencyNetWorth } from "@/services/analytics-service";
import { formatCurrency } from "@/lib/utils";
import { Card } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import {
  CreateAccountForm,
  formatAccountType,
  getAccountRole,
} from "@/components/accounts/account-management-ui";

export function BalanceBreakdown({
  accounts,
  netWorthByCurrency,
  convertedCurrency,
  showCreateForm,
  isCreating,
  onAdd,
  onCreate,
  onCancelCreate,
  onEdit,
  onDelete,
}: {
  accounts: Account[];
  netWorthByCurrency: CurrencyNetWorth[];
  convertedCurrency: string | null;
  showCreateForm: boolean;
  isCreating: boolean;
  onAdd: () => void;
  onCreate: (values: CreateAccountPayload) => void;
  onCancelCreate: () => void;
  onEdit: (account: Account) => void;
  onDelete: (account: Account) => void;
}) {
  const currencies = Array.from(new Set(accounts.map((account) => account.currency))).sort();
  const multiCurrency = currencies.length > 1;
  const bucketsByCurrency = new Map(netWorthByCurrency.map((bucket) => [bucket.currency, bucket]));

  return (
    <section className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-lg font-semibold tracking-tight text-foreground">Balance Breakdown</h2>
          <p className="mt-0.5 text-sm text-muted-foreground">
            Account balances behind your assets, liabilities, and net worth.
          </p>
          {convertedCurrency && (
            <p className="mt-1 text-xs text-muted-foreground">
              Account rows show native balances; converted totals above are shown in {convertedCurrency}.
            </p>
          )}
        </div>
        <Button size="sm" onClick={onAdd}>
          <Plus className="h-4 w-4" /> Add account
        </Button>
      </div>

      {showCreateForm && (
        <CreateAccountForm
          onSubmit={onCreate}
          onCancel={onCancelCreate}
          isPending={isCreating}
        />
      )}

      {accounts.length === 0 ? (
        <Card className="flex flex-col items-start gap-3 p-5 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <p className="font-medium text-foreground">No accounts yet</p>
            <p className="mt-1 text-sm text-muted-foreground">
              Add a cash, bank, savings, or credit account to start tracking balances.
            </p>
          </div>
          <Button size="sm" onClick={onAdd}>
            Add account
          </Button>
        </Card>
      ) : (
        <div className="space-y-3">
          {currencies.map((currency) => (
            <CurrencyBalanceGroup
              key={currency}
              currency={currency}
              accounts={accounts.filter((account) => account.currency === currency)}
              bucket={bucketsByCurrency.get(currency)}
              showHeading={multiCurrency}
              onEdit={onEdit}
              onDelete={onDelete}
            />
          ))}
        </div>
      )}
    </section>
  );
}

function CurrencyBalanceGroup({
  currency,
  accounts,
  bucket,
  showHeading,
  onEdit,
  onDelete,
}: {
  currency: string;
  accounts: Account[];
  bucket?: CurrencyNetWorth;
  showHeading: boolean;
  onEdit: (account: Account) => void;
  onDelete: (account: Account) => void;
}) {
  return (
    <Card className="overflow-hidden p-0">
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-border bg-secondary/50 px-4 py-3">
        <div>
          <h3 className="font-semibold tracking-tight text-foreground">
            {showHeading ? currency : "Accounts"}
          </h3>
          {bucket && (
            <p className="mt-0.5 text-xs text-muted-foreground">
              Assets {formatCurrency(bucket.totalAssets, currency)} - Liabilities{" "}
              {formatCurrency(bucket.totalLiabilities, currency)} - Net{" "}
              {formatCurrency(bucket.netWorth, currency)}
            </p>
          )}
        </div>
        <span className="rounded-md bg-card px-2 py-1 font-mono text-[11px] font-semibold tabular-nums text-muted-foreground">
          {currency}
        </span>
      </div>
      <div className="hidden grid-cols-[minmax(0,1.35fr)_0.8fr_0.7fr_minmax(8rem,0.8fr)_4.5rem] gap-3 border-b border-border px-4 py-2 text-xs font-medium uppercase tracking-wide text-muted-foreground md:grid">
        <span>Account</span>
        <span>Type</span>
        <span>Role</span>
        <span className="text-right">Balance</span>
        <span className="text-right">Actions</span>
      </div>
      <div className="divide-y divide-border">
        {accounts.map((account) => {
          const role = getAccountRole(account.accountType);
          return (
            <div
              key={account.id}
              className="grid gap-3 px-4 py-3 transition-colors hover:bg-secondary/50 md:grid-cols-[minmax(0,1.35fr)_0.8fr_0.7fr_minmax(8rem,0.8fr)_4.5rem] md:items-center"
            >
              <div className="min-w-0">
                <p className="truncate font-semibold text-foreground" title={account.name}>
                  {account.name}
                </p>
                <p className="mt-1 text-xs text-muted-foreground md:hidden">
                  {formatAccountType(account.accountType)} - {role}
                </p>
              </div>
              <p className="hidden text-sm text-muted-foreground md:block">
                {formatAccountType(account.accountType)}
              </p>
              <span
                className={`w-fit rounded-full border px-2 py-0.5 text-xs font-medium ${
                  role === "Asset"
                    ? "border-emerald-500/20 bg-emerald-500/10 text-emerald-700 dark:text-emerald-400"
                    : "border-rose-500/20 bg-rose-500/10 text-rose-600 dark:text-rose-400"
                }`}
              >
                {role}
              </span>
              <p
                className="font-mono text-base font-bold tabular-nums text-foreground md:text-right"
                title={formatCurrency(account.currentBalance, account.currency)}
              >
                {formatCurrency(account.currentBalance, account.currency)}
              </p>
              <div className="flex items-center gap-1 md:justify-end">
                <button
                  onClick={() => onEdit(account)}
                  className="rounded-lg p-1.5 text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/40"
                  title="Edit account"
                  aria-label={`Edit ${account.name}`}
                >
                  <Pencil className="h-4 w-4" />
                </button>
                <button
                  onClick={() => onDelete(account)}
                  className="rounded-lg p-1.5 text-muted-foreground transition-colors hover:bg-rose-500/10 hover:text-rose-500 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/40 dark:hover:text-rose-400"
                  title="Delete account"
                  aria-label={`Delete ${account.name}`}
                >
                  <Trash2 className="h-4 w-4" />
                </button>
              </div>
            </div>
          );
        })}
      </div>
    </Card>
  );
}
