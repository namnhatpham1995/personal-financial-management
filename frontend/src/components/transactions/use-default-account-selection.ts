import { useEffect } from "react";
import type { UseFormSetValue } from "react-hook-form";
import type { Account } from "@/services/account-service";
import type { TransactionFormValues } from "@/app/dashboard/transactions/transaction-form-schema";

/**
 * A plain `<select>` shows its first option as selected by default without
 * firing a change event, so react-hook-form's watch() (and anything derived
 * from it, like cross-currency detection) never learns that value until the
 * user actually touches the control. This keeps `accountId`/`transferAccountId`
 * form state in sync with what the selects visually show, so derived state
 * (e.g. the destination-amount field for a cross-currency transfer) is correct
 * from the first render instead of only after a manual reselect.
 */
export function useDefaultAccountSelection(params: {
  isEditing: boolean;
  txType: TransactionFormValues["transactionType"] | undefined;
  accountId: number | undefined;
  transferAccountId: number | undefined;
  accounts: Account[];
  setValue: UseFormSetValue<TransactionFormValues>;
}) {
  const { isEditing, txType, accountId, transferAccountId, accounts, setValue } = params;

  useEffect(() => {
    if (isEditing || accounts.length === 0 || accountId) return;
    setValue("accountId", accounts[0].id);
  }, [isEditing, accountId, accounts, setValue]);

  useEffect(() => {
    if (txType !== "TRANSFER" || transferAccountId) return;
    const fallback = accounts.find((a) => a.id !== Number(accountId));
    if (fallback) setValue("transferAccountId", fallback.id);
  }, [txType, accountId, transferAccountId, accounts, setValue]);
}
