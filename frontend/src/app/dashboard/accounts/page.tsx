"use client";

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import {
  accountService,
  Account,
  CreateAccountPayload,
  UpdateAccountPayload,
} from "@/services/account-service";
import { formatCurrency } from "@/lib/utils";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { toast } from "sonner";
import { Plus, Trash2, Pencil } from "lucide-react";

// ── Schemas ──────────────────────────────────────────────────────────────────

const createSchema = z.object({
  name: z.string().min(1),
  accountType: z.enum(["CASH", "BANK", "CREDIT_CARD", "SAVINGS", "OTHER"]),
  currency: z.string().min(3).max(3).default("USD"),
  initialBalance: z.string().default("0"),
});

const editSchema = z.object({
  name: z.string().min(1),
  accountType: z.enum(["CASH", "BANK", "CREDIT_CARD", "SAVINGS", "OTHER"]),
  currency: z.string().min(3).max(3),
  initialBalance: z.string(),
});

type CreateFormValues = z.infer<typeof createSchema>;
type EditFormValues = z.infer<typeof editSchema>;

// ── Page ─────────────────────────────────────────────────────────────────────

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
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">Accounts</h1>
        <button
          onClick={() => { setShowCreateForm(!showCreateForm); setEditingAccount(null); }}
          className="flex items-center gap-2 rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90"
        >
          <Plus className="h-4 w-4" /> New Account
        </button>
      </div>

      {showCreateForm && (
        <CreateAccountForm
          onSubmit={(values) => createMutation.mutate(values)}
          onCancel={() => setShowCreateForm(false)}
          isPending={createMutation.isPending}
        />
      )}

      {isLoading ? (
        <p className="text-muted-foreground">Loading…</p>
      ) : (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {accounts.map((acc) => (
            <div key={acc.id} className="rounded-xl border border-border bg-card p-5 shadow-sm">
              <div className="flex items-start justify-between">
                <div>
                  <p className="text-xs uppercase text-muted-foreground">{acc.accountType}</p>
                  <p className="mt-0.5 font-semibold">{acc.name}</p>
                </div>
                <div className="flex items-center gap-1">
                  <button
                    onClick={() => { setEditingAccount(acc); setShowCreateForm(false); }}
                    className="text-muted-foreground hover:text-foreground"
                    title="Edit account"
                  >
                    <Pencil className="h-4 w-4" />
                  </button>
                  <button
                    onClick={() => setDeleteTarget(acc)}
                    className="text-muted-foreground hover:text-destructive"
                    title="Delete account"
                  >
                    <Trash2 className="h-4 w-4" />
                  </button>
                </div>
              </div>
              <p className="mt-3 text-2xl font-bold">
                {formatCurrency(acc.currentBalance, acc.currency)}
              </p>
            </div>
          ))}
        </div>
      )}

      {/* Edit dialog */}
      {editingAccount && (
        <EditAccountDialog
          account={editingAccount}
          onSubmit={(values) => editMutation.mutate({ id: editingAccount.id, data: values })}
          onCancel={() => setEditingAccount(null)}
          isPending={editMutation.isPending}
        />
      )}

      {/* Delete confirmation dialog */}
      {deleteTarget && (
        <DeleteConfirmDialog
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

// ── Create form ───────────────────────────────────────────────────────────────

function CreateAccountForm({
  onSubmit,
  onCancel,
  isPending,
}: {
  onSubmit: (v: CreateAccountPayload) => void;
  onCancel: () => void;
  isPending: boolean;
}) {
  const { register, handleSubmit, formState: { errors } } =
    useForm<CreateFormValues>({ resolver: zodResolver(createSchema) });

  return (
    <div className="rounded-xl border border-border bg-card p-5">
      <h2 className="mb-4 font-semibold">Add Account</h2>
      <form onSubmit={handleSubmit(onSubmit)} className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <Field label="Name" error={errors.name?.message}>
          <input {...register("name")} className={inputCls} />
        </Field>
        <Field label="Type" error={errors.accountType?.message}>
          <select {...register("accountType")} className={inputCls}>
            {ACCOUNT_TYPES.map((t) => <option key={t}>{t}</option>)}
          </select>
        </Field>
        <Field label="Currency (ISO code)" error={errors.currency?.message}>
          <input {...register("currency")} defaultValue="USD" placeholder="USD" className={inputCls} />
        </Field>
        <Field label="Initial Balance" error={errors.initialBalance?.message}>
          <input {...register("initialBalance")} defaultValue="0" type="number" step="0.01" className={inputCls} />
        </Field>
        <div className="sm:col-span-2 flex gap-2">
          <button type="submit" disabled={isPending} className={primaryBtn}>Save</button>
          <button type="button" onClick={onCancel} className={secondaryBtn}>Cancel</button>
        </div>
      </form>
    </div>
  );
}

// ── Edit dialog ───────────────────────────────────────────────────────────────

function EditAccountDialog({
  account,
  onSubmit,
  onCancel,
  isPending,
}: {
  account: Account;
  onSubmit: (v: EditFormValues) => void;
  onCancel: () => void;
  isPending: boolean;
}) {
  const { register, handleSubmit, formState: { errors } } = useForm<EditFormValues>({
    resolver: zodResolver(editSchema),
    defaultValues: {
      name: account.name,
      accountType: account.accountType as EditFormValues["accountType"],
      currency: account.currency,
      initialBalance: account.initialBalance,
    },
  });

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
      <div className="w-full max-w-md rounded-xl border border-border bg-card p-6 shadow-xl">
        <h2 className="mb-4 text-lg font-semibold">Edit Account</h2>
        <form onSubmit={handleSubmit(onSubmit)} className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label="Name" error={errors.name?.message}>
            <input {...register("name")} className={inputCls} />
          </Field>
          <Field label="Type" error={errors.accountType?.message}>
            <select {...register("accountType")} className={inputCls}>
              {ACCOUNT_TYPES.map((t) => <option key={t}>{t}</option>)}
            </select>
          </Field>
          <Field label="Currency (ISO code)" error={errors.currency?.message}>
            <input {...register("currency")} className={inputCls} />
          </Field>
          <Field label="Initial Balance" error={errors.initialBalance?.message}>
            <input {...register("initialBalance")} type="number" step="0.01" className={inputCls} />
          </Field>
          <p className="sm:col-span-2 text-xs text-muted-foreground">
            Changing initial balance will recompute the current balance. Changing currency relabels
            existing amounts without converting them.
          </p>
          <div className="sm:col-span-2 flex gap-2">
            <button type="submit" disabled={isPending} className={primaryBtn}>Save changes</button>
            <button type="button" onClick={onCancel} className={secondaryBtn}>Cancel</button>
          </div>
        </form>
      </div>
    </div>
  );
}

// ── Delete confirmation dialog ────────────────────────────────────────────────

function DeleteConfirmDialog({
  account,
  transactionCount,
  onConfirm,
  onCancel,
  isPending,
}: {
  account: Account;
  transactionCount: number | null;
  onConfirm: () => void;
  onCancel: () => void;
  isPending: boolean;
}) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
      <div className="w-full max-w-sm rounded-xl border border-border bg-card p-6 shadow-xl">
        <h2 className="mb-2 text-lg font-semibold text-destructive">Delete Account</h2>
        <p className="text-sm text-muted-foreground">
          Are you sure you want to delete <strong>{account.name}</strong>?
        </p>
        {transactionCount === null ? (
          <p className="mt-2 text-sm text-muted-foreground">Checking connected transactions…</p>
        ) : transactionCount === 0 ? (
          <p className="mt-2 text-sm text-muted-foreground">This account has no transactions.</p>
        ) : (
          <p className="mt-2 rounded-md bg-destructive/10 p-3 text-sm text-destructive">
            <strong>{transactionCount} transaction{transactionCount !== 1 ? "s" : ""}</strong> connected
            to this account will also be permanently deleted.
          </p>
        )}
        <div className="mt-4 flex gap-2">
          <button
            onClick={onConfirm}
            disabled={isPending || transactionCount === null}
            className="rounded-md bg-destructive px-4 py-2 text-sm font-medium text-white hover:bg-destructive/90 disabled:opacity-50"
          >
            {isPending ? "Deleting…" : "Yes, delete"}
          </button>
          <button onClick={onCancel} className={secondaryBtn}>Cancel</button>
        </div>
      </div>
    </div>
  );
}

// ── Shared ────────────────────────────────────────────────────────────────────

const ACCOUNT_TYPES = ["CASH", "BANK", "CREDIT_CARD", "SAVINGS", "OTHER"];

const inputCls =
  "w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring";
const primaryBtn =
  "rounded-md bg-primary px-4 py-2 text-sm text-primary-foreground hover:bg-primary/90 disabled:opacity-50";
const secondaryBtn = "rounded-md border px-4 py-2 text-sm hover:bg-accent";

function Field({
  label,
  error,
  children,
}: {
  label: string;
  error?: string;
  children: React.ReactNode;
}) {
  return (
    <div>
      <label className="mb-1 block text-sm font-medium">{label}</label>
      {children}
      {error && <p className="mt-1 text-xs text-destructive">{error}</p>}
    </div>
  );
}
