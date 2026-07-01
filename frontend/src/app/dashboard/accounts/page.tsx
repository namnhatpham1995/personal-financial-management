"use client";

import Link from "next/link";
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { ArrowLeft, Pencil, Plus, Trash2 } from "lucide-react";
import {
  accountService,
  Account,
  CreateAccountPayload,
  UpdateAccountPayload,
} from "@/services/account-service";
import { formatCurrency } from "@/lib/utils";
import { Card } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import {
  AccountSkeletons,
  CreateAccountForm,
  DeleteAccountDialog,
  EditAccountDialog,
  formatAccountType,
} from "@/components/accounts/account-management-ui";

export default function AccountsPage() {
  const qc = useQueryClient();
  const [showCreateForm, setShowCreateForm] = useState(false);
  const [editingAccount, setEditingAccount] = useState<Account | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<Account | null>(null);

  const { data: accounts = [], isLoading } = useQuery({
    queryKey: ["accounts"],
    queryFn: accountService.list,
  });

  const { data: deletePreview } = useQuery({
    queryKey: ["deletePreview", deleteTarget?.id],
    queryFn: () => accountService.deletePreview(deleteTarget!.id),
    enabled: deleteTarget !== null,
  });

  const invalidateAll = () => {
    qc.invalidateQueries({ queryKey: ["accounts"] });
    qc.invalidateQueries({ queryKey: ["netWorth"] });
    qc.invalidateQueries({ queryKey: ["transactions"] });
  };

  const createMutation = useMutation({
    mutationFn: (data: CreateAccountPayload) => accountService.create(data),
    onSuccess: () => {
      invalidateAll();
      setShowCreateForm(false);
      toast.success("Account created");
    },
    onError: () => toast.error("Failed to create account"),
  });

  const editMutation = useMutation({
    mutationFn: ({ id, data }: { id: number; data: UpdateAccountPayload }) =>
      accountService.update(id, data),
    onSuccess: () => {
      invalidateAll();
      setEditingAccount(null);
      toast.success("Account updated");
    },
    onError: () => toast.error("Failed to update account"),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => accountService.delete(id),
    onSuccess: () => {
      invalidateAll();
      qc.invalidateQueries({ queryKey: ["spending"] });
      qc.invalidateQueries({ queryKey: ["trend"] });
      setDeleteTarget(null);
      toast.success("Account deleted");
    },
    onError: () => toast.error("Failed to delete account"),
  });

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <Link
            href="/dashboard"
            className="mb-2 inline-flex items-center gap-1 text-sm font-medium text-muted-foreground transition-colors hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/40"
          >
            <ArrowLeft className="h-4 w-4" />
            Overview
          </Link>
          <h1 className="text-2xl font-bold tracking-tight text-foreground">Accounts</h1>
          <p className="mt-1 text-sm text-muted-foreground">
            Add, edit, or remove the accounts included in your dashboard balances.
          </p>
        </div>
        <Button
          onClick={() => {
            setShowCreateForm(!showCreateForm);
            setEditingAccount(null);
          }}
        >
          <Plus className="h-4 w-4" /> New account
        </Button>
      </div>

      {showCreateForm && (
        <CreateAccountForm
          onSubmit={(values) => createMutation.mutate(values)}
          onCancel={() => setShowCreateForm(false)}
          isPending={createMutation.isPending}
        />
      )}

      {isLoading ? (
        <AccountSkeletons />
      ) : (
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
          {accounts.length === 0 ? (
            <Card className="p-5 sm:col-span-2">
              <p className="font-medium text-foreground">No accounts yet</p>
              <p className="mt-1 text-sm text-muted-foreground">
                Create your first account to start tracking balances and transactions.
              </p>
            </Card>
          ) : (
            accounts.map((acc) => (
              <Card key={acc.id} className="p-5">
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                      {formatAccountType(acc.accountType)}
                    </p>
                    <p className="mt-1 truncate font-semibold text-foreground" title={acc.name}>
                      {acc.name}
                    </p>
                  </div>
                  <div className="flex items-center gap-1">
                    <button
                      onClick={() => {
                        setEditingAccount(acc);
                        setShowCreateForm(false);
                      }}
                      className="rounded-lg p-1.5 text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/40"
                      title="Edit account"
                      aria-label={`Edit ${acc.name}`}
                    >
                      <Pencil className="h-4 w-4" />
                    </button>
                    <button
                      onClick={() => setDeleteTarget(acc)}
                      className="rounded-lg p-1.5 text-muted-foreground transition-colors hover:bg-rose-500/10 hover:text-rose-500 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/40 dark:hover:text-rose-400"
                      title="Delete account"
                      aria-label={`Delete ${acc.name}`}
                    >
                      <Trash2 className="h-4 w-4" />
                    </button>
                  </div>
                </div>
                <p className="mt-4 truncate font-mono text-2xl font-bold tabular-nums text-foreground">
                  {formatCurrency(acc.currentBalance, acc.currency)}
                </p>
                <p className="mt-1 font-mono text-xs font-semibold tabular-nums text-muted-foreground">
                  {acc.currency}
                </p>
              </Card>
            ))
          )}
        </div>
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
