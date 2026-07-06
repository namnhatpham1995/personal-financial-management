"use client";

import { Pencil, Plus, Trash2 } from "lucide-react";
import type { Account, CreateAccountPayload } from "@/services/account-service";
import { formatCurrency } from "@/lib/utils";
import { Card } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import {
  CreateAccountForm,
  formatAccountType,
} from "@/components/accounts/account-management-ui";

/**
 * Top-of-page account management entry point: add-account action, inline create
 * form, and the empty state shown when the user has no accounts anywhere yet.
 * Existing accounts render per currency section via AccountsGroup below —
 * this component no longer groups or lists them itself.
 */
export function BalanceBreakdown({
  hasAccounts,
  showCreateForm,
  isCreating,
  onAdd,
  onCreate,
  onCancelCreate,
}: {
  hasAccounts: boolean;
  showCreateForm: boolean;
  isCreating: boolean;
  onAdd: () => void;
  onCreate: (values: CreateAccountPayload) => void;
  onCancelCreate: () => void;
}) {
  return (
    <section className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-lg font-semibold tracking-tight text-foreground">Accounts</h2>
          <p className="mt-0.5 text-sm text-muted-foreground">Your account balances.</p>
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

      {!hasAccounts && !showCreateForm && (
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
      )}
    </section>
  );
}

/** Grid of account boxes for one currency section on Overview. */
export function AccountsGroup({
  accounts,
  onEdit,
  onDelete,
  onOpenDetail,
}: {
  accounts: Account[];
  onEdit: (account: Account) => void;
  onDelete: (account: Account) => void;
  onOpenDetail: (account: Account) => void;
}) {
  if (accounts.length === 0) {
    return <p className="text-sm text-muted-foreground">No accounts in this currency yet.</p>;
  }

  return (
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
            {formatAccountType(account.accountType)}
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
            className="inline-flex min-h-11 min-w-11 items-center justify-center rounded-sm text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/40"
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
            className="inline-flex min-h-11 min-w-11 items-center justify-center rounded-sm text-muted-foreground transition-colors hover:bg-destructive/10 hover:text-destructive focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/40"
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
