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
import { Card } from "@/components/ui/card";

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
    onSuccess: () => { invalidateAll(); setShowCreateForm(false); toast.success("Account created"); },
    onError: () => toast.error("Failed to create account"),
  });

  const editMutation = useMutation({
    mutationFn: ({ id, data }: { id: number; data: UpdateAccountPayload }) => accountService.update(id, data),
    onSuccess: () => { invalidateAll(); setEditingAccount(null); toast.success("Account updated"); },
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
        <h1 className="text-2xl font-bold tracking-tight text-slate-100">Accounts</h1>
        <button
          onClick={() => { setShowCreateForm(!showCreateForm); setEditingAccount(null); }}
          className="flex items-center gap-2 rounded-lg bg-emerald-500/10 border border-emerald-500/20 px-4 py-2 text-sm font-medium text-emerald-400 hover:bg-emerald-500/20 transition-colors"
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
        <p className="text-slate-500">Loading…</p>
      ) : (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {accounts.map((acc) => (
            <Card key={acc.id} className="p-5">
              <div className="flex items-start justify-between">
                <div>
                  <p className="text-xs font-medium uppercase tracking-wide text-slate-500">{acc.accountType}</p>
                  <p className="mt-0.5 font-semibold text-slate-100">{acc.name}</p>
                </div>
                <div className="flex items-center gap-1">
                  <button
                    onClick={() => { setEditingAccount(acc); setShowCreateForm(false); }}
                    className="rounded-lg p-1.5 text-slate-500 hover:bg-slate-800/60 hover:text-slate-200 transition-colors"
                    title="Edit account"
                  >
                    <Pencil className="h-4 w-4" />
                  </button>
                  <button
                    onClick={() => setDeleteTarget(acc)}
                    className="rounded-lg p-1.5 text-slate-500 hover:bg-rose-500/10 hover:text-rose-400 transition-colors"
                    title="Delete account"
                  >
                    <Trash2 className="h-4 w-4" />
                  </button>
                </div>
              </div>
              <p className="mt-3 font-mono tabular-nums text-2xl font-bold text-slate-100">
                {formatCurrency(acc.currentBalance, acc.currency)}
              </p>
            </Card>
          ))}
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

function CreateAccountForm({ onSubmit, onCancel, isPending }: { onSubmit: (v: CreateAccountPayload) => void; onCancel: () => void; isPending: boolean }) {
  const { register, handleSubmit, formState: { errors } } = useForm<CreateFormValues>({ resolver: zodResolver(createSchema) });

  return (
    <div className="rounded-xl border border-slate-800/60 bg-slate-900/40 backdrop-blur-sm p-5">
      <h2 className="mb-4 font-semibold tracking-tight text-slate-100">Add Account</h2>
      <form onSubmit={handleSubmit(onSubmit)} className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <Field label="Name" error={errors.name?.message}><input {...register("name")} className={inputCls} /></Field>
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

function EditAccountDialog({ account, onSubmit, onCancel, isPending }: { account: Account; onSubmit: (v: EditFormValues) => void; onCancel: () => void; isPending: boolean }) {
  const { register, handleSubmit, formState: { errors } } = useForm<EditFormValues>({
    resolver: zodResolver(editSchema),
    defaultValues: { name: account.name, accountType: account.accountType as EditFormValues["accountType"], currency: account.currency, initialBalance: account.initialBalance },
  });

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm p-4">
      <div className="w-full max-w-md rounded-xl border border-slate-800/60 bg-slate-900/95 p-6 shadow-2xl">
        <h2 className="mb-4 text-lg font-semibold tracking-tight text-slate-100">Edit Account</h2>
        <form onSubmit={handleSubmit(onSubmit)} className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field label="Name" error={errors.name?.message}><input {...register("name")} className={inputCls} /></Field>
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
          <p className="sm:col-span-2 text-xs text-slate-500">
            Changing initial balance will recompute the current balance. Changing currency relabels existing amounts without converting them.
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

function DeleteConfirmDialog({ account, transactionCount, onConfirm, onCancel, isPending }: { account: Account; transactionCount: number | null; onConfirm: () => void; onCancel: () => void; isPending: boolean }) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm p-4">
      <div className="w-full max-w-sm rounded-xl border border-slate-800/60 bg-slate-900/95 p-6 shadow-2xl">
        <h2 className="mb-2 text-lg font-semibold tracking-tight text-rose-400">Delete Account</h2>
        <p className="text-sm text-slate-400">
          Are you sure you want to delete <strong className="text-slate-100">{account.name}</strong>?
        </p>
        {transactionCount === null ? (
          <p className="mt-2 text-sm text-slate-500">Checking connected transactions…</p>
        ) : transactionCount === 0 ? (
          <p className="mt-2 text-sm text-slate-500">This account has no transactions.</p>
        ) : (
          <p className="mt-2 rounded-lg border border-rose-500/20 bg-rose-500/10 p-3 text-sm text-rose-400">
            <strong>{transactionCount} transaction{transactionCount !== 1 ? "s" : ""}</strong> connected to this account will also be permanently deleted.
          </p>
        )}
        <div className="mt-4 flex gap-2">
          <button
            onClick={onConfirm}
            disabled={isPending || transactionCount === null}
            className="rounded-lg bg-rose-500/10 border border-rose-500/20 px-4 py-2 text-sm font-medium text-rose-400 hover:bg-rose-500/20 disabled:opacity-50 transition-colors"
          >
            {isPending ? "Deleting…" : "Yes, delete"}
          </button>
          <button onClick={onCancel} className={secondaryBtn}>Cancel</button>
        </div>
      </div>
    </div>
  );
}

const ACCOUNT_TYPES = ["CASH", "BANK", "CREDIT_CARD", "SAVINGS", "OTHER"];

const inputCls =
  "w-full rounded-lg border border-slate-800/60 bg-slate-900/60 px-3 py-2 text-sm text-slate-200 placeholder:text-slate-600 focus:outline-none focus:ring-2 focus:ring-emerald-500/40 focus:border-emerald-500/40 transition-colors";
const primaryBtn =
  "rounded-lg bg-emerald-500/10 border border-emerald-500/20 px-4 py-2 text-sm font-medium text-emerald-400 hover:bg-emerald-500/20 disabled:opacity-50 transition-colors";
const secondaryBtn =
  "rounded-lg border border-slate-800/60 px-4 py-2 text-sm text-slate-400 hover:bg-slate-800/60 transition-colors";

function Field({ label, error, children }: { label: string; error?: string; children: React.ReactNode }) {
  return (
    <div>
      <label className="mb-1.5 block text-xs font-medium uppercase tracking-wide text-slate-500">{label}</label>
      {children}
      {error && <p className="mt-1 text-xs text-rose-400">{error}</p>}
    </div>
  );
}
