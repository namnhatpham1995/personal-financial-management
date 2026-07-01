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
  onOpenDetail,
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
  onOpenDetail: (account: Account) => void;
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
              onOpenDetail={onOpenDetail}
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
  onOpenDetail,
}: {
  currency: string;
  accounts: Account[];
  bucket?: CurrencyNetWorth;
  showHeading: boolean;
  onEdit: (account: Account) => void;
  onDelete: (account: Account) => void;
  onOpenDetail: (account: Account) => void;
}) {
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-3">
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
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
        {accounts.map((account) => (
          <AccountBox
            key={account.id}
            account={account}
            onEdit={onEdit}
            onDelete={onDelete}
            onOpenDetail={onOpenDetail}
          />
        ))}
      </div>
    </div>
  );
}

/** Clickable summary box for one account. Opens the account detail view on activation;
 *  inner edit/delete buttons stop propagation so they don't also open the detail. */
function AccountBox({
  account,
  onEdit,
  onDelete,
  onOpenDetail,
}: {
  account: Account;
  onEdit: (account: Account) => void;
  onDelete: (account: Account) => void;
  onOpenDetail: (account: Account) => void;
}) {
  const role = getAccountRole(account.accountType);
  return (
    <Card
      role="button"
      tabIndex={0}
      onClick={() => onOpenDetail(account)}
      onKeyDown={(e) => {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          onOpenDetail(account);
        }
      }}
      className="cursor-pointer p-5 transition-colors hover:bg-secondary/50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/40"
      title={`View ${account.name} details`}
      aria-label={`View ${account.name} details`}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
            {formatAccountType(account.accountType)} · <span>{role}</span>
          </p>
          <p className="mt-1 truncate font-semibold text-foreground" title={account.name}>
            {account.name}
          </p>
        </div>
        <div className="flex items-center gap-1">
          <button
            onClick={(e) => {
              e.stopPropagation();
              onEdit(account);
            }}
            className="rounded-lg p-1.5 text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/40"
            title="Edit account"
            aria-label={`Edit ${account.name}`}
          >
            <Pencil className="h-4 w-4" />
          </button>
          <button
            onClick={(e) => {
              e.stopPropagation();
              onDelete(account);
            }}
            className="rounded-lg p-1.5 text-muted-foreground transition-colors hover:bg-rose-500/10 hover:text-rose-500 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/40 dark:hover:text-rose-400"
            title="Delete account"
            aria-label={`Delete ${account.name}`}
          >
            <Trash2 className="h-4 w-4" />
          </button>
        </div>
      </div>
      <p
        className="mt-4 truncate font-mono text-2xl font-bold tabular-nums text-foreground"
        title={formatCurrency(account.currentBalance, account.currency)}
      >
        {formatCurrency(account.currentBalance, account.currency)}
      </p>
    </Card>
  );
}
