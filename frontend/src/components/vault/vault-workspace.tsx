"use client";

import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { Inbox, Upload, ChevronLeft, ChevronRight } from "lucide-react";
import { accountService } from "@/services/account-service";
import { agentRunService, isAgentFeatureUnavailable } from "@/services/agent-run-service";
import {
  vaultService,
  type VaultDocument,
  type VaultDocumentType,
  type VaultStage,
} from "@/services/vault-service";
import { NEEDS_ATTENTION_STAGES } from "@/lib/vault-stage";
import { VaultFilterBar, type StagePreset } from "@/components/vault/vault-filter-bar";
import { VaultDocumentTable } from "@/components/vault/vault-document-table";
import { VaultUploadPanel } from "@/components/vault/vault-upload-panel";
import { StatementImportWizard } from "@/components/vault/statement-import-wizard";
import { DocumentReaderModal } from "@/components/vault/document-reader-modal";

const PAGE_SIZE = 50;

function stagesForPreset(preset: StagePreset): VaultStage[] | undefined {
  if (preset === "attention") return [...NEEDS_ATTENTION_STAGES];
  if (preset === "imported") return ["IMPORTED"];
  return undefined;
}

export function VaultWorkspace() {
  const t = useTranslations("vault");
  const qc = useQueryClient();

  const [stagePreset, setStagePreset] = useState<StagePreset>("attention");
  const [type, setType] = useState<VaultDocumentType | null>(null);
  const [accountId, setAccountId] = useState<number | null>(null);
  const [page, setPage] = useState(0);
  const [uploading, setUploading] = useState(false);
  const [reviewingStatementId, setReviewingStatementId] = useState<string | null>(null);
  const [readingDoc, setReadingDoc] = useState<{ id: string; filename?: string } | null>(null);

  const { data: accounts } = useQuery({ queryKey: ["accounts"], queryFn: () => accountService.list() });
  const accountList = accounts ?? [];

  const { error: agentRunsError } = useQuery({
    queryKey: ["agent-runs"],
    queryFn: () => agentRunService.list(),
    retry: false,
  });
  const agentFeatureUnavailable = isAgentFeatureUnavailable(agentRunsError);

  const types = type ? [type] : undefined;
  const stages = stagesForPreset(stagePreset);

  const listQuery = useQuery({
    queryKey: ["vault-workspace", types, stages, accountId, page],
    queryFn: () => vaultService.workspace({ types, stages, accountId: accountId ?? undefined, page, size: PAGE_SIZE }),
  });

  const countsQuery = useQuery({
    queryKey: ["vault-workspace-counts", types, accountId],
    queryFn: () => vaultService.workspaceStageCounts({ types, accountId: accountId ?? undefined }),
  });

  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ["vault-workspace"] });
    qc.invalidateQueries({ queryKey: ["vault-workspace-counts"] });
  };

  const documents = listQuery.data?.content ?? [];
  // Counts are fetched with the type/account filter but not the stage filter, so their total is
  // "documents matching type+account, across every stage" — the right signal for "is this vault
  // (or this type/account slice of it) truly empty" vs "the stage preset filtered everything out".
  // A stage preset alone (including the default "attention") never counts as making it non-empty.
  const totalMatchingTypeAndAccount = Object.values(countsQuery.data ?? {}).reduce((sum, n) => sum + (n ?? 0), 0);
  const isFilteredEmptyResult = documents.length === 0 && totalMatchingTypeAndAccount > 0;

  if (reviewingStatementId) {
    return (
      <div className="max-w-3xl space-y-3">
        <button
          onClick={() => setReviewingStatementId(null)}
          className="text-xs text-muted-foreground transition-colors hover:text-foreground"
        >
          {t("backToList")}
        </button>
        <StatementImportWizard
          documentId={reviewingStatementId}
          onComplete={() => {
            invalidate();
            setReviewingStatementId(null);
          }}
        />
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between gap-3">
        <VaultFilterBar
          stagePreset={stagePreset}
          onStagePresetChange={(preset) => {
            setStagePreset(preset);
            setPage(0);
          }}
          type={type}
          onTypeChange={(nextType) => {
            setType(nextType);
            setPage(0);
          }}
          accountId={accountId}
          onAccountChange={(nextAccountId) => {
            setAccountId(nextAccountId);
            setPage(0);
          }}
          accounts={accountList}
          counts={countsQuery.data ?? {}}
        />
        <button
          onClick={() => setUploading((value) => !value)}
          className="inline-flex shrink-0 items-center gap-1.5 rounded-full bg-primary/10 px-3.5 py-2 text-sm font-medium text-primary hover:bg-primary/20"
        >
          <Upload className="h-4 w-4" />
          {t("workspace.upload")}
        </button>
      </div>

      {uploading && (
        <VaultUploadPanel
          accounts={accountList}
          defaultAccountId={accountId}
          onUploaded={() => {
            invalidate();
            setUploading(false);
          }}
          onClose={() => setUploading(false)}
        />
      )}

      {listQuery.isLoading && (
        <div className="space-y-2">
          {[0, 1, 2].map((i) => (
            <div key={i} className="h-11 animate-pulse rounded-lg bg-muted/30" />
          ))}
        </div>
      )}
      {listQuery.isError && <p className="text-sm text-destructive">{t("loadFailed")}</p>}

      {!listQuery.isLoading && !listQuery.isError && documents.length === 0 && (
        <div className="flex flex-col items-center gap-3 py-16 text-center">
          <Inbox className="h-10 w-10 text-muted-foreground/40" />
          {isFilteredEmptyResult ? (
            <>
              <p className="text-sm font-medium text-foreground">{t("workspace.emptyFilterResult.title")}</p>
              <p className="text-xs text-muted-foreground">{t("workspace.emptyFilterResult.body")}</p>
              <button
                onClick={() => {
                  setStagePreset("all");
                  setType(null);
                  setAccountId(null);
                  setPage(0);
                }}
                className="text-xs font-medium text-primary hover:underline"
              >
                {t("filters.clear")}
              </button>
            </>
          ) : (
            <>
              <p className="text-sm font-medium text-foreground">{t("workspace.emptyState.title")}</p>
              <p className="text-xs text-muted-foreground">{t("workspace.emptyState.body")}</p>
            </>
          )}
        </div>
      )}

      {!listQuery.isLoading && !listQuery.isError && documents.length > 0 && (
        <VaultDocumentTable
          documents={documents}
          accounts={accountList}
          agentFeatureUnavailable={agentFeatureUnavailable}
          onOpenDocument={(doc: VaultDocument) => setReadingDoc({ id: doc.id, filename: doc.originalFilename })}
          onReviewStatement={setReviewingStatementId}
          invalidate={invalidate}
        />
      )}

      {(listQuery.data?.totalPages ?? 0) > 1 && (
        <div className="flex items-center justify-between border-t border-border pt-3">
          <span className="text-xs text-muted-foreground">
            {t("pagination.pageOf", { current: page + 1, total: listQuery.data?.totalPages ?? 1 })}
          </span>
          <div className="flex gap-2">
            <button
              type="button"
              aria-label={t("pagination.prevAria")}
              disabled={page === 0}
              onClick={() => setPage((value) => Math.max(0, value - 1))}
              className="inline-flex min-h-9 min-w-9 items-center justify-center rounded border border-border text-muted-foreground hover:bg-secondary disabled:opacity-40"
            >
              <ChevronLeft className="h-4 w-4" />
            </button>
            <button
              type="button"
              aria-label={t("pagination.nextAria")}
              disabled={page >= (listQuery.data?.totalPages ?? 1) - 1}
              onClick={() => setPage((value) => value + 1)}
              className="inline-flex min-h-9 min-w-9 items-center justify-center rounded border border-border text-muted-foreground hover:bg-secondary disabled:opacity-40"
            >
              <ChevronRight className="h-4 w-4" />
            </button>
          </div>
        </div>
      )}

      <DocumentReaderModal
        documentId={readingDoc?.id ?? null}
        originalFilename={readingDoc?.filename}
        onClose={() => setReadingDoc(null)}
      />
    </div>
  );
}
