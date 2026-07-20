"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Plus } from "lucide-react";
import { toast } from "sonner";
import { useTranslations } from "next-intl";
import {
  accountService,
  Account,
  CreateAccountPayload,
  UpdateAccountPayload,
} from "@/services/account-service";
import type { IncomeExpenseTrend, SpendingByCategory } from "@/services/analytics-service";
import {
  CreateAccountForm,
  DeleteAccountDialog,
  EditAccountDialog,
} from "@/components/accounts/account-management-ui";
import { AccountDetailDialog } from "@/components/accounts/account-detail-dialog";
import { CurrencySection } from "@/components/overview/currency-section";
import { Button } from "@/components/ui/button";
import { useIdempotencyKey } from "@/lib/use-idempotency-key";
import { getIdempotencyErrorCode } from "@/lib/idempotency-error";

interface CurrencyDetailBodyProps {
  currency: string;
  /** Native total balance for this currency; undefined when the user holds no accounts in it. */
  nativeTotal?: string;
  trend: IncomeExpenseTrend[];
  spending: SpendingByCategory[];
  accounts: Account[];
}

/**
 * Full per-currency content: add-account entry point, charts + recent activity,
 * account boxes with CRUD, and budget progress. Shared by the currency detail
 * route and (for single-currency users) Overview's inline collapse.
 */
export function CurrencyDetailBody({
  currency,
  nativeTotal,
  trend,
  spending,
  accounts,
}: CurrencyDetailBodyProps) {
  const qc = useQueryClient();
  const t = useTranslations("dashboard");
  const tAccounts = useTranslations("accounts");
  const tCommon = useTranslations("common");

  const [showCreateForm, setShowCreateForm] = useState(false);
  const [editingAccount, setEditingAccount] = useState<Account | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<Account | null>(null);
  const [detailAccount, setDetailAccount] = useState<Account | null>(null);

  const { data: deletePreview } = useQuery({
    queryKey: ["deletePreview", deleteTarget?.id],
    queryFn: () => accountService.deletePreview(deleteTarget!.id),
    enabled: deleteTarget !== null,
  });

  const invalidateAccountViews = () => {
    qc.invalidateQueries({ queryKey: ["accounts"] });
    qc.invalidateQueries({ queryKey: ["balances"] });
    qc.invalidateQueries({ queryKey: ["balancesSummary"] });
    qc.invalidateQueries({ queryKey: ["transactions"] });
    qc.invalidateQueries({ queryKey: ["recentTransactions"] });
    qc.invalidateQueries({ queryKey: ["spending"] });
    qc.invalidateQueries({ queryKey: ["trend"] });
  };

  const createIdempotency = useIdempotencyKey(null);

  const createMutation = useMutation({
    mutationFn: (data: CreateAccountPayload) =>
      accountService.create(data, createIdempotency.resolve(data)),
    onSuccess: () => {
      createIdempotency.clear();
      invalidateAccountViews();
      setShowCreateForm(false);
      toast.success(t("toast.accountCreated"));
    },
    onError: (err) => {
      const idempotencyCode = getIdempotencyErrorCode(err);
      if (idempotencyCode === "idempotency_key_conflict") {
        createIdempotency.clear();
        toast.error(t("toast.accountCreateFailed"));
      } else if (idempotencyCode === "operation_in_progress") {
        toast.error(tCommon("operationInProgress"));
      } else {
        toast.error(t("toast.accountCreateFailed"));
      }
    },
  });

  const editMutation = useMutation({
    mutationFn: ({ id, data }: { id: number; data: UpdateAccountPayload }) =>
      accountService.update(id, data),
    onSuccess: () => {
      invalidateAccountViews();
      setEditingAccount(null);
      toast.success(t("toast.accountUpdated"));
    },
    onError: () => toast.error(t("toast.accountUpdateFailed")),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => accountService.delete(id),
    onSuccess: () => {
      invalidateAccountViews();
      setDeleteTarget(null);
      toast.success(t("toast.accountDeleted"));
    },
    onError: () => toast.error(t("toast.accountDeleteFailed")),
  });

  return (
    <div className="space-y-4">
      {accounts.length > 0 && (
        <div className="flex justify-end">
          <Button
            size="sm"
            variant="secondary"
            onClick={() => {
              setShowCreateForm(true);
              setEditingAccount(null);
            }}
          >
            <Plus className="h-4 w-4" /> {tAccounts("addAccount")}
          </Button>
        </div>
      )}

      {showCreateForm && (
        <CreateAccountForm
          defaultCurrency={currency}
          onSubmit={(values) => createMutation.mutate(values)}
          onCancel={() => setShowCreateForm(false)}
          isPending={createMutation.isPending}
        />
      )}

      <CurrencySection
        currency={currency}
        nativeTotal={nativeTotal}
        trend={trend}
        spending={spending}
        accounts={accounts}
        onEditAccount={(account) => {
          setEditingAccount(account);
          setShowCreateForm(false);
        }}
        onDeleteAccount={setDeleteTarget}
        onOpenAccountDetail={setDetailAccount}
        onAddAccount={() => setShowCreateForm(true)}
      />

      {detailAccount && (
        <AccountDetailDialog account={detailAccount} onClose={() => setDetailAccount(null)} />
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
