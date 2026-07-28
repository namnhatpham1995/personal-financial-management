"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";
import type { Account } from "@/services/account-service";
import type { VaultDocumentType } from "@/services/vault-service";
import { StatementUploadDropzone } from "@/components/vault/statement-upload-dropzone";
import { ReceiptUploadViewer } from "@/components/vault/receipt-upload-viewer";

interface Props {
  accounts: Account[];
  /** Preselected when a single-account filter is active; null when viewing all accounts. */
  defaultAccountId: number | null;
  onUploaded: () => void;
  onClose: () => void;
}

/**
 * Upload flow: choose type + destination account, then the matching dropzone. Decoupled from
 * the workspace's list filter — `defaultAccountId` only seeds the initial selection (via
 * useState's lazy initializer), so a filter change made elsewhere while this panel stays open
 * does not retroactively change the upload destination. The dropzone/viewer is withheld until
 * an account is chosen, so a blank filter ("all accounts") forces an explicit choice here.
 */
export function VaultUploadPanel({ accounts, defaultAccountId, onUploaded, onClose }: Props) {
  const t = useTranslations("vault");
  const [type, setType] = useState<VaultDocumentType>("STATEMENT");
  const [accountId, setAccountId] = useState<number | null>(defaultAccountId);

  return (
    <div className="max-w-md space-y-4 rounded-lg border border-border bg-card p-4">
      <div className="flex gap-1">
        {(["STATEMENT", "RECEIPT"] as const).map((option) => (
          <button
            key={option}
            type="button"
            onClick={() => setType(option)}
            className={
              type === option
                ? "rounded-full bg-primary/10 px-3 py-1.5 text-xs font-medium text-primary"
                : "rounded-full border border-border px-3 py-1.5 text-xs font-medium text-muted-foreground hover:bg-hover-surface"
            }
          >
            {t(`tabs.${option === "STATEMENT" ? "statement" : "receipt"}`)}
          </button>
        ))}
      </div>

      <div className="space-y-2">
        <label className="text-sm font-medium text-foreground" htmlFor="vault-upload-account">
          {t("uploadAccount")}
        </label>
        <select
          id="vault-upload-account"
          className="w-full rounded-md border border-border bg-card px-3.5 py-2.5 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-primary/40"
          value={accountId ?? ""}
          onChange={(event) => setAccountId(Number(event.target.value) || null)}
        >
          <option value="">{t("selectAccount")}</option>
          {accounts.map((account) => (
            <option key={account.id} value={account.id}>
              {account.name}
            </option>
          ))}
        </select>
      </div>

      {accountId ? (
        type === "STATEMENT" ? (
          <StatementUploadDropzone accountId={accountId} onUploaded={onUploaded} />
        ) : (
          <ReceiptUploadViewer accountId={accountId} onLinked={onUploaded} />
        )
      ) : (
        <p className="text-sm text-muted-foreground">{t("selectAccountToUpload")}</p>
      )}

      <button onClick={onClose} className="text-xs text-muted-foreground transition-colors hover:text-foreground">
        {t("backToList")}
      </button>
    </div>
  );
}
