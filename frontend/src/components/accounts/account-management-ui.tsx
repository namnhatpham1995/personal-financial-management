"use client";

import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import type {
  Account,
  CreateAccountPayload,
  UpdateAccountPayload,
} from "@/services/account-service";
import { Card } from "@/components/ui/card";
import { Button } from "@/components/ui/button";

const accountSchema = z.object({
  name: z.string().min(1),
  accountType: z.enum(["CASH", "BANK", "CREDIT_CARD", "SAVINGS", "OTHER"]),
  currency: z.string().min(3).max(3),
  initialBalance: z.string(),
});

const createSchema = accountSchema.extend({
  currency: z.string().min(3).max(3).default("USD"),
  initialBalance: z.string().default("0"),
});

const editSchema = accountSchema;

export type CreateAccountFormValues = z.infer<typeof createSchema>;
export type EditAccountFormValues = z.infer<typeof editSchema>;

const inputCls =
  "w-full rounded-lg border border-border bg-card px-3 py-2 text-base text-foreground placeholder:text-muted-foreground/50 transition-colors focus:border-primary/40 focus:outline-none focus:ring-2 focus:ring-primary/40";

const ACCOUNT_TYPES = ["CASH", "BANK", "CREDIT_CARD", "SAVINGS", "OTHER"];

export function CreateAccountForm({
  onSubmit,
  onCancel,
  isPending,
  framed = true,
}: {
  onSubmit: (v: CreateAccountPayload) => void;
  onCancel: () => void;
  isPending: boolean;
  framed?: boolean;
}) {
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<CreateAccountFormValues>({ resolver: zodResolver(createSchema) });

  const form = (
    <>
      <h2 className="mb-4 font-semibold tracking-tight text-foreground">Add account</h2>
      <form onSubmit={handleSubmit(onSubmit)} className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <AccountFields register={register} errors={errors} includeDefaults />
        <div className="flex gap-2 sm:col-span-2">
          <Button type="submit" disabled={isPending}>
            {isPending ? "Saving..." : "Save"}
          </Button>
          <Button type="button" variant="secondary" onClick={onCancel}>
            Cancel
          </Button>
        </div>
      </form>
    </>
  );

  return framed ? <Card className="p-5">{form}</Card> : form;
}

export function EditAccountDialog({
  account,
  onSubmit,
  onCancel,
  isPending,
}: {
  account: Account;
  onSubmit: (v: UpdateAccountPayload) => void;
  onCancel: () => void;
  isPending: boolean;
}) {
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<EditAccountFormValues>({
    resolver: zodResolver(editSchema),
    defaultValues: {
      name: account.name,
      accountType: account.accountType as EditAccountFormValues["accountType"],
      currency: account.currency,
      initialBalance: account.initialBalance,
    },
  });

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4 backdrop-blur-sm">
      <div className="w-full max-w-md rounded-lg border border-border bg-card p-6 shadow-card">
        <h2 className="mb-4 text-lg font-semibold tracking-tight text-foreground">Edit account</h2>
        <form onSubmit={handleSubmit(onSubmit)} className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <AccountFields register={register} errors={errors} />
          <p className="text-xs text-muted-foreground sm:col-span-2">
            Changing the initial balance recomputes the current balance. Changing currency relabels existing amounts without converting them.
          </p>
          <div className="flex gap-2 sm:col-span-2">
            <Button type="submit" disabled={isPending}>
              {isPending ? "Saving..." : "Save changes"}
            </Button>
            <Button type="button" variant="secondary" onClick={onCancel}>
              Cancel
            </Button>
          </div>
        </form>
      </div>
    </div>
  );
}

export function DeleteAccountDialog({
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
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4 backdrop-blur-sm">
      <div className="w-full max-w-sm rounded-lg border border-border bg-card p-6 shadow-card">
        <h2 className="mb-2 text-lg font-semibold tracking-tight text-rose-600 dark:text-rose-400">
          Delete account
        </h2>
        <p className="text-sm text-muted-foreground">
          Delete <strong className="text-foreground">{account.name}</strong>?
        </p>
        {transactionCount === null ? (
          <p className="mt-2 text-sm text-muted-foreground">Checking connected transactions...</p>
        ) : transactionCount === 0 ? (
          <p className="mt-2 text-sm text-muted-foreground">This account has no transactions.</p>
        ) : (
          <p className="mt-2 rounded-lg border border-rose-500/20 bg-rose-500/10 p-3 text-sm text-rose-600 dark:text-rose-400">
            <strong>
              {transactionCount} transaction{transactionCount !== 1 ? "s" : ""}
            </strong>{" "}
            connected to this account will also be permanently deleted.
          </p>
        )}
        <div className="mt-4 flex gap-2">
          <Button
            variant="destructive"
            onClick={onConfirm}
            disabled={isPending || transactionCount === null}
          >
            {isPending ? "Deleting..." : "Delete account"}
          </Button>
          <Button variant="secondary" onClick={onCancel}>
            Cancel
          </Button>
        </div>
      </div>
    </div>
  );
}

export function AccountSkeletons() {
  return (
    <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
      {Array.from({ length: 3 }).map((_, index) => (
        <Card key={index} className="space-y-4 p-5">
          <div className="h-3 w-20 rounded bg-secondary" />
          <div className="h-4 w-32 rounded bg-secondary" />
          <div className="h-7 w-36 rounded bg-secondary/70" />
        </Card>
      ))}
    </div>
  );
}

export function formatAccountType(type: string): string {
  return type.replaceAll("_", " ").toLowerCase();
}

export function getAccountRole(type: string): "Asset" | "Liability" {
  return type === "CREDIT_CARD" ? "Liability" : "Asset";
}

function AccountFields({
  register,
  errors,
  includeDefaults = false,
}: {
  register: ReturnType<typeof useForm<CreateAccountFormValues>>["register"];
  errors: Partial<Record<keyof CreateAccountFormValues, { message?: string }>>;
  includeDefaults?: boolean;
}) {
  return (
    <>
      <Field label="Name" error={errors.name?.message}>
        <input {...register("name")} className={inputCls} />
      </Field>
      <Field label="Type" error={errors.accountType?.message}>
        <select {...register("accountType")} className={inputCls}>
          {ACCOUNT_TYPES.map((t) => (
            <option key={t}>{t}</option>
          ))}
        </select>
      </Field>
      <Field label="Currency (ISO code)" error={errors.currency?.message}>
        <input
          {...register("currency")}
          defaultValue={includeDefaults ? "USD" : undefined}
          placeholder="USD"
          className={inputCls}
        />
      </Field>
      <Field label="Initial balance" error={errors.initialBalance?.message}>
        <input
          {...register("initialBalance")}
          defaultValue={includeDefaults ? "0" : undefined}
          type="number"
          step="0.01"
          className={inputCls}
        />
      </Field>
    </>
  );
}

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
      <label className="block">
        <span className="mb-1.5 block text-xs font-medium uppercase tracking-wide text-muted-foreground">
          {label}
        </span>
        {children}
      </label>
      {error && <p className="mt-1 text-xs text-rose-500 dark:text-rose-400">{error}</p>}
    </div>
  );
}
