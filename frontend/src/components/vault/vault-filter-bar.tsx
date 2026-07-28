"use client";

import { useTranslations } from "next-intl";
import type { Account } from "@/services/account-service";
import type { VaultDocumentType, VaultStageCounts } from "@/services/vault-service";
import { NEEDS_ATTENTION_STAGES } from "@/lib/vault-stage";
import { cn } from "@/lib/utils";

export type StagePreset = "attention" | "all" | "imported";

interface Props {
  stagePreset: StagePreset;
  onStagePresetChange: (preset: StagePreset) => void;
  type: VaultDocumentType | null;
  onTypeChange: (type: VaultDocumentType | null) => void;
  accountId: number | null;
  onAccountChange: (accountId: number | null) => void;
  accounts: Account[];
  counts: VaultStageCounts;
}

function sumCounts(counts: VaultStageCounts, stages: readonly string[]): number {
  return stages.reduce((total, stage) => total + (counts[stage as keyof VaultStageCounts] ?? 0), 0);
}

export function VaultFilterBar({
  stagePreset,
  onStagePresetChange,
  type,
  onTypeChange,
  accountId,
  onAccountChange,
  accounts,
  counts,
}: Props) {
  const t = useTranslations("vault");
  const needsAttentionCount = sumCounts(counts, NEEDS_ATTENTION_STAGES);
  const importedCount = counts.IMPORTED ?? 0;

  const presetButton = (preset: StagePreset, label: string, count?: number) => (
    <button
      type="button"
      onClick={() => onStagePresetChange(preset)}
      className={cn(
        "inline-flex items-center gap-1.5 rounded-full px-3 py-1.5 text-xs font-medium transition-colors",
        stagePreset === preset
          ? "bg-primary/10 text-primary"
          : "border border-border text-muted-foreground hover:bg-hover-surface"
      )}
    >
      {label}
      {count != null && count > 0 && (
        <span
          className={cn(
            "rounded-full px-1.5 py-0.5 text-[10px]",
            stagePreset === preset ? "bg-primary/20 text-primary" : "bg-secondary text-muted-foreground"
          )}
        >
          {count}
        </span>
      )}
    </button>
  );

  return (
    <div className="flex flex-wrap items-center gap-2">
      {presetButton("attention", t("filters.needsAttention"), needsAttentionCount)}
      {presetButton("all", t("filters.all"))}
      {presetButton("imported", t("filters.imported"), importedCount)}

      <span className="mx-1 h-5 w-px bg-border" aria-hidden="true" />

      <select
        aria-label={t("workspace.allTypes")}
        className="rounded-md border border-border bg-card px-2.5 py-1.5 text-xs text-foreground focus:outline-none focus:ring-2 focus:ring-primary/40"
        value={type ?? ""}
        onChange={(event) => onTypeChange((event.target.value || null) as VaultDocumentType | null)}
      >
        <option value="">{t("workspace.allTypes")}</option>
        <option value="STATEMENT">{t("tabs.statement")}</option>
        <option value="RECEIPT">{t("tabs.receipt")}</option>
      </select>

      <select
        aria-label={t("accountFilter")}
        className="rounded-md border border-border bg-card px-2.5 py-1.5 text-xs text-foreground focus:outline-none focus:ring-2 focus:ring-primary/40"
        value={accountId ?? ""}
        onChange={(event) => onAccountChange(Number(event.target.value) || null)}
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
