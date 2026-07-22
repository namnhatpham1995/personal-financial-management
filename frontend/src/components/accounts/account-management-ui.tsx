"use client";

import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useTranslations } from "next-intl";
import { useAccountTypeLabel } from "@/lib/enum-labels";
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
  // Number field: schema, register(), and the DOM input type must all agree on
  // type=number, or the error message flips between "expected string" and
  // "expected number" across the first vs. a later submit attempt.
  initialBalance: z.coerce.number().min(0),
});

const createSchema = accountSchema.extend({
  currency: z.string().min(3).max(3).default("USD"),
  initialBalance: z.coerce.number().min(0).default(0),
});

const editSchema = accountSchema;

export type CreateAccountFormValues = z.infer<typeof createSchema>;
export type EditAccountFormValues = z.infer<typeof editSchema>;

const inputCls =
  "w-full rounded-md border border-border bg-card px-3.5 py-2.5 text-base text-foreground placeholder:text-muted-foreground/50 transition-colors focus:border-primary/40 focus:outline-none focus:ring-2 focus:ring-primary/40";

const ACCOUNT_TYPES = ["CASH", "BANK", "CREDIT_CARD", "SAVINGS", "OTHER"];

export function CreateAccountForm({
  onSubmit,
  onCancel,
  isPending,
  framed = true,
  defaultCurrency,
}: {
  onSubmit: (v: CreateAccountPayload) => void;
  onCancel: () => void;
  isPending: boolean;
  framed?: boolean;
  /** Pre-fills the currency field, e.g. when adding an account from a currency detail page. */
  defaultCurrency?: string;
}) {
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<CreateAccountFormValues>({ resolver: zodResolver(createSchema) });
  const t = useTranslations("accounts");
  const getAccountTypeLabel = useAccountTypeLabel();
  const tCommon = useTranslations("common");

  const form = (
    <>
      <h2 className="mb-4 font-semibold tracking-tight text-foreground">{t("addAccount")}</h2>
      <form onSubmit={handleSubmit(onSubmit)} className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <AccountFields
          register={register}
          errors={errors}
          includeDefaults
          defaultCurrency={defaultCurrency}
        />
        <div className="flex gap-2 sm:col-span-2">
          <Button type="submit" disabled={isPending}>
            {isPending ? t("saving") : tCommon("save")}
          </Button>
          <Button type="button" variant="secondary" onClick={onCancel}>
            {tCommon("cancel")}
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
  const t = useTranslations("accounts");
  const tCommon = useTranslations("common");

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4 backdrop-blur-sm">
      <div className="w-full max-w-md rounded-lg border border-border bg-card p-6 shadow-card">
        <h2 className="mb-4 text-lg font-semibold tracking-tight text-foreground">{t("editAccount")}</h2>
        <form onSubmit={handleSubmit(onSubmit)} className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <AccountFields register={register} errors={errors} />
          <p className="text-xs text-muted-foreground sm:col-span-2">
            {t("editAccountHint")}
          </p>
          <div className="flex gap-2 sm:col-span-2">
            <Button type="submit" disabled={isPending}>
              {isPending ? t("saving") : t("saveChanges")}
            </Button>
            <Button type="button" variant="secondary" onClick={onCancel}>
              {tCommon("cancel")}
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
  const t = useTranslations("accounts");
  const tCommon = useTranslations("common");

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4 backdrop-blur-sm">
      <div className="w-full max-w-sm rounded-lg border border-border bg-card p-6 shadow-card">
        <h2 className="mb-2 text-lg font-semibold tracking-tight text-destructive">
          {t("deleteAccount")}
        </h2>
        <p className="text-sm text-muted-foreground">
          {t.rich("deleteConfirm", {
            accountName: account.name,
            strong: (chunks) => <strong className="text-foreground">{chunks}</strong>,
          })}
        </p>
        {transactionCount === null ? (
          <p className="mt-2 text-sm text-muted-foreground">{t("checkingConnectedTransactions")}</p>
        ) : transactionCount === 0 ? (
          <p className="mt-2 text-sm text-muted-foreground">{t("noTransactions")}</p>
        ) : (
          <p className="mt-2 rounded-md border border-destructive/20 bg-destructive/10 p-3 text-sm text-destructive">
            {t("transactionsWillBeDeleted", { count: transactionCount })}
          </p>
        )}
        <div className="mt-4 flex gap-2">
          <Button
            variant="destructive"
            onClick={onConfirm}
            disabled={isPending || transactionCount === null}
          >
            {isPending ? t("deleting") : t("deleteAccount")}
          </Button>
          <Button variant="secondary" onClick={onCancel}>
            {tCommon("cancel")}
          </Button>
        </div>
      </div>
    </div>
  );
}

function AccountFields({
  register,
  errors,
  includeDefaults = false,
  defaultCurrency,
}: {
  register: ReturnType<typeof useForm<CreateAccountFormValues>>["register"];
  errors: Partial<Record<keyof CreateAccountFormValues, { message?: string }>>;
  includeDefaults?: boolean;
  defaultCurrency?: string;
}) {
  const t = useTranslations("accounts.fields");
  const getAccountTypeLabel = useAccountTypeLabel();
  return (
    <>
      <Field label={t("name")} error={errors.name?.message}>
        <input {...register("name")} className={inputCls} />
      </Field>
      <Field label={t("type")} error={errors.accountType?.message}>
        <select {...register("accountType")} className={inputCls}>
          {ACCOUNT_TYPES.map((type) => (
            <option key={type} value={type}>{getAccountTypeLabel(type)}</option>
          ))}
        </select>
      </Field>
      <Field label={t("currency")} error={errors.currency?.message}>
        <input
          {...register("currency")}
          defaultValue={defaultCurrency ?? (includeDefaults ? "USD" : undefined)}
          placeholder="USD"
          className={inputCls}
        />
      </Field>
      <Field label={t("initialBalance")} error={errors.initialBalance?.message}>
        <input
          {...register("initialBalance", { valueAsNumber: true })}
          defaultValue={includeDefaults ? 0 : undefined}
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
      {error && <p className="mt-1 text-xs text-destructive">{error}</p>}
    </div>
  );
}
