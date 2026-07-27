"use client";

import type { Account } from "@/services/account-service";
import { useTranslations } from "next-intl";

interface Props {
  accounts: Account[];
  accountId: number | null;
  onChange: (accountId: number | null) => void;
}

export function VaultAccountFilter({ accounts, accountId, onChange }: Props) {
  const t = useTranslations("vault");
  return (
    <div className="max-w-sm space-y-2">
      <label className="text-sm font-medium text-foreground" htmlFor="vault-account-filter">
        {t("accountFilter")}
      </label>
      <select
        id="vault-account-filter"
        className="w-full rounded-md border border-border bg-card px-3.5 py-2.5 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-primary/40"
        value={accountId ?? ""}
        onChange={(event) => onChange(Number(event.target.value) || null)}
      >
        <option value="">{t("allAccounts")}</option>
        {accounts.map((account) => (
          <option key={account.id} value={account.id}>
            {account.name}
          </option>
        ))}
      </select>
    </div>
  );
}
