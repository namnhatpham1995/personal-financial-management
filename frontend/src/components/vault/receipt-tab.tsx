"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { vaultService } from "@/services/vault-service";
import { accountService } from "@/services/account-service";
import { agentRunService, isAgentFeatureUnavailable } from "@/services/agent-run-service";
import { ReceiptUploadViewer } from "@/components/vault/receipt-upload-viewer";
import { DocumentReaderModal } from "@/components/vault/document-reader-modal";
import { VaultAccountFilter } from "@/components/vault/vault-account-filter";
import { VaultReassignmentDialog } from "@/components/vault/vault-reassignment-dialog";
import { formatDate } from "@/lib/utils";
import { useLocale, useTranslations } from "next-intl";
import Link from "next/link";
import { Receipt, Sparkles, Loader2, ChevronLeft, ChevronRight } from "lucide-react";
import { toast } from "sonner";
import { useIngestionStatusLabel } from "@/lib/enum-labels";

export function ReceiptTab() {
  const t = useTranslations("vault");
  const tCommon = useTranslations("common");
  const locale = useLocale();
  const qc = useQueryClient();
  const getIngestionStatusLabel = useIngestionStatusLabel();
  const [accountId, setAccountId] = useState<number | null>(null);
  const [uploadAccountId, setUploadAccountId] = useState<number | null>(null);
  const [page, setPage] = useState(0);
  const [readingDoc, setReadingDoc] = useState<{ id: string; filename?: string } | null>(null);

  const { data: accounts } = useQuery({ queryKey: ["accounts"], queryFn: () => accountService.list() });
  const accountList = accounts ?? [];
  const listQueryKey = ["vault-by-type", "RECEIPT", ...(accountId == null ? [] : [accountId]), page] as const;
  const listQuery = useQuery({
    queryKey: listQueryKey,
    queryFn: () => vaultService.listByType("RECEIPT", accountId ?? undefined, page, 100),
  });

  const { error: agentRunsError } = useQuery({
    queryKey: ["agent-runs"],
    queryFn: () => agentRunService.list(),
    retry: false,
  });
  const agentFeatureUnavailable = isAgentFeatureUnavailable(agentRunsError);
  const invalidateList = () => qc.invalidateQueries({ queryKey: ["vault-by-type", "RECEIPT"] });

  const startIngestionMut = useMutation({
    mutationFn: (documentId: string) => agentRunService.start(documentId),
    onSuccess: () => {
      toast.success(t("ingestion.toast.started"));
      invalidateList();
      qc.invalidateQueries({ queryKey: ["agent-runs"] });
    },
    onError: () => toast.error(t("ingestion.toast.startFailed")),
  });

  const receipts = listQuery.data?.content ?? [];
  const accountName = (id?: number) =>
    id == null ? t("unassigned") : accountList.find((account) => account.id === id)?.name ?? t("unassigned");

  return (
    <div className="space-y-4">
      <VaultAccountFilter
        accounts={accountList}
        accountId={accountId}
        onChange={(nextAccountId) => {
          setAccountId(nextAccountId);
          setUploadAccountId(nextAccountId);
          setPage(0);
        }}
      />
      {listQuery.isLoading && <p className="text-sm text-muted-foreground">{tCommon("loading")}</p>}
      {listQuery.isError && <p className="text-sm text-destructive">{t("loadFailed")}</p>}

      <div className="max-w-lg space-y-4">
        <div className="space-y-2">
          <label className="text-sm font-medium text-foreground" htmlFor="receipt-upload-account">
            {t("uploadAccount")}
          </label>
          <select
            id="receipt-upload-account"
            className="w-full rounded-md border border-border bg-card px-3.5 py-2.5 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-primary/40"
            value={uploadAccountId ?? ""}
            onChange={(event) => setUploadAccountId(Number(event.target.value) || null)}
          >
            <option value="">{t("selectAccount")}</option>
            {accountList.map((account) => (
              <option key={account.id} value={account.id}>{account.name}</option>
            ))}
          </select>
        </div>
        {uploadAccountId ? (
          <ReceiptUploadViewer accountId={uploadAccountId} onLinked={invalidateList} />
        ) : (
          <p className="text-sm text-muted-foreground">{t("selectAccountToUpload")}</p>
        )}

        <div>
          <h3 className="mb-2 text-sm font-semibold text-foreground">{t("availableFiles")}</h3>
          {receipts.length === 0 ? (
            <p className="text-sm text-muted-foreground">{t("noReceipts")}</p>
          ) : (
            <div className="divide-y divide-border rounded-lg border border-border">
              {receipts.map((doc) => (
                <div key={doc.id} className="flex items-center gap-3 px-3 py-2">
                  <Receipt className="h-4 w-4 shrink-0 text-muted-foreground" />
                  <button
                    onClick={() => setReadingDoc({ id: doc.id, filename: doc.originalFilename })}
                    className="min-w-0 flex-1 truncate text-left text-sm text-foreground hover:underline"
                  >
                    {doc.originalFilename ?? doc.id}
                  </button>
                  <span className="hidden shrink-0 text-xs text-muted-foreground sm:inline">{accountName(doc.accountId)}</span>
                  <span className="shrink-0 text-xs text-muted-foreground">
                    {formatDate(doc.capturedAt.split("T")[0], locale)}
                  </span>
                  {!agentFeatureUnavailable &&
                    (doc.ingestionStatus ? (
                      <Link href="/dashboard/receipts" className="shrink-0 text-xs font-medium text-primary hover:underline">
                        {t("ingestion.viewRun")} - {getIngestionStatusLabel(doc.ingestionStatus)}
                      </Link>
                    ) : (
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
                    ))}
                  <VaultReassignmentDialog document={doc} accounts={accountList} onSuccess={invalidateList} />
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      {(listQuery.data?.totalPages ?? 0) > 1 && (
        <div className="flex max-w-lg items-center justify-between border-t border-border pt-3">
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

      <DocumentReaderModal documentId={readingDoc?.id ?? null} originalFilename={readingDoc?.filename} onClose={() => setReadingDoc(null)} />
    </div>
  );
}
