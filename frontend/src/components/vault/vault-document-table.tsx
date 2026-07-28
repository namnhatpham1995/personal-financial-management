"use client";

import { Fragment } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useLocale, useTranslations } from "next-intl";
import Link from "next/link";
import { toast } from "sonner";
import { FileText, Loader2, Receipt, Sparkles } from "lucide-react";
import type { Account } from "@/services/account-service";
import { agentRunService } from "@/services/agent-run-service";
import { vaultService, type VaultDocument } from "@/services/vault-service";
import { deriveDisplayStage, VAULT_STAGE_BADGE_CLASS, type VaultDisplayStage } from "@/lib/vault-stage";
import { useVaultStageLabel, useIngestionStatusLabel } from "@/lib/enum-labels";
import { cn, formatDate } from "@/lib/utils";
import { VaultDocumentRowActions } from "@/components/vault/vault-document-row-actions";

interface Props {
  documents: VaultDocument[];
  accounts: Account[];
  agentFeatureUnavailable: boolean;
  onOpenDocument: (doc: VaultDocument) => void;
  onReviewStatement: (documentId: string) => void;
  invalidate: () => void;
}

function accountName(accounts: Account[], id: number | undefined, unassignedLabel: string): string {
  if (id == null) return unassignedLabel;
  return accounts.find((account) => account.id === id)?.name ?? unassignedLabel;
}

function monthGroupKey(capturedAt: string): string {
  const date = new Date(capturedAt);
  return `${date.getFullYear()}-${String(date.getMonth()).padStart(2, "0")}`;
}

function groupByMonth(documents: VaultDocument[]): Array<[string, VaultDocument[]]> {
  const groups = new Map<string, VaultDocument[]>();
  for (const doc of documents) {
    const key = monthGroupKey(doc.capturedAt);
    const group = groups.get(key);
    if (group) group.push(doc);
    else groups.set(key, [doc]);
  }
  return Array.from(groups.entries());
}

export function VaultDocumentTable({
  documents,
  accounts,
  agentFeatureUnavailable,
  onOpenDocument,
  onReviewStatement,
  invalidate,
}: Props) {
  const t = useTranslations("vault");
  const locale = useLocale();
  const qc = useQueryClient();
  const getStageLabel = useVaultStageLabel();
  const getIngestionStatusLabel = useIngestionStatusLabel();

  const deleteMut = useMutation({
    mutationFn: (id: string) => vaultService.deleteById(id),
    onSuccess: () => {
      toast.success(t("toast.documentDeleted"));
      invalidate();
    },
    onError: () => toast.error(t("toast.deleteFailed")),
  });

  const startIngestionMut = useMutation({
    mutationFn: (documentId: string) => agentRunService.start(documentId),
    onSuccess: () => {
      toast.success(t("ingestion.toast.started"));
      invalidate();
      qc.invalidateQueries({ queryKey: ["agent-runs"] });
    },
    onError: () => toast.error(t("ingestion.toast.startFailed")),
  });

  const monthFormatter = new Intl.DateTimeFormat(locale, { month: "long", year: "numeric" });

  const renderPrimaryAction = (doc: VaultDocument, stage: VaultDisplayStage) => {
    if (doc.type === "STATEMENT") {
      if (stage === "READY_TO_IMPORT" || stage === "NEEDS_REVIEW") {
        return (
          <button
            onClick={() => onReviewStatement(doc.id)}
            className="shrink-0 text-xs font-medium text-primary hover:underline"
          >
            {t("workspace.review")}
          </button>
        );
      }
      return null;
    }

    // RECEIPT
    if (agentFeatureUnavailable) return null;
    if (doc.ingestionStatus) {
      return (
        <Link href="/dashboard/receipts" className="shrink-0 text-xs font-medium text-primary hover:underline">
          {t("ingestion.viewRun")} - {getIngestionStatusLabel(doc.ingestionStatus)}
        </Link>
      );
    }
    return (
      <button
        onClick={() => startIngestionMut.mutate(doc.id)}
        disabled={startIngestionMut.isPending}
        className="inline-flex shrink-0 items-center gap-1 text-xs font-medium text-primary hover:underline disabled:opacity-50"
      >
        {startIngestionMut.isPending && startIngestionMut.variables === doc.id ? (
          <Loader2 className="h-3 w-3 animate-spin" />
        ) : (
          <Sparkles className="h-3 w-3" />
        )}
        {startIngestionMut.isPending && startIngestionMut.variables === doc.id
          ? t("ingestion.starting")
          : t("ingestion.start")}
      </button>
    );
  };

  return (
    <div className="overflow-hidden rounded-lg border border-border">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-border bg-muted/30 text-left text-xs font-medium text-muted-foreground">
            <th className="px-3 py-2">{t("workspace.table.name")}</th>
            <th className="px-3 py-2">{t("workspace.table.stage")}</th>
            <th className="hidden px-3 py-2 sm:table-cell">{t("workspace.table.account")}</th>
            <th className="px-3 py-2">{t("workspace.table.date")}</th>
            <th className="px-3 py-2" />
            <th className="px-3 py-2" />
          </tr>
        </thead>
        <tbody className="divide-y divide-border">
          {groupByMonth(documents).map(([groupKey, docs]) => (
            <Fragment key={groupKey}>
              <tr>
                <td colSpan={6} className="bg-muted/20 px-3 py-1.5 text-xs text-muted-foreground">
                  {monthFormatter.format(new Date(docs[0].capturedAt))}
                </td>
              </tr>
              {docs.map((doc) => {
                const stage = deriveDisplayStage(doc, agentFeatureUnavailable);
                const Icon = doc.type === "RECEIPT" ? Receipt : FileText;
                return (
                  <tr key={doc.id} className="transition-colors hover:bg-muted/20">
                    <td className="px-3 py-2">
                      <button
                        onClick={() => onOpenDocument(doc)}
                        className="flex min-w-0 items-center gap-2 text-left text-foreground hover:underline"
                      >
                        <Icon className="h-4 w-4 shrink-0 text-muted-foreground" />
                        <span className="truncate">{doc.originalFilename ?? doc.id}</span>
                      </button>
                    </td>
                    <td className="px-3 py-2">
                      <span
                        className={cn(
                          "inline-flex rounded-full px-2 py-0.5 text-xs font-medium",
                          VAULT_STAGE_BADGE_CLASS[stage]
                        )}
                      >
                        {getStageLabel(stage)}
                      </span>
                    </td>
                    <td className="hidden px-3 py-2 text-muted-foreground sm:table-cell">
                      {accountName(accounts, doc.accountId, t("unassigned"))}
                    </td>
                    <td className="px-3 py-2 text-muted-foreground">{formatDate(doc.capturedAt.split("T")[0], locale)}</td>
                    <td className="px-3 py-2">{renderPrimaryAction(doc, stage)}</td>
                    <td className="px-3 py-2 text-right">
                      <VaultDocumentRowActions
                        document={doc}
                        accounts={accounts}
                        onReassigned={invalidate}
                        onDelete={() => {
                          if (confirm(t("deleteConfirm"))) deleteMut.mutate(doc.id);
                        }}
                      />
                    </td>
                  </tr>
                );
              })}
            </Fragment>
          ))}
        </tbody>
      </table>
    </div>
  );
}
