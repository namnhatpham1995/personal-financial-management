"use client";

import { Suspense, useState } from "react";
import Link from "next/link";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { vaultService, VaultDocument } from "@/services/vault-service";
import { accountService } from "@/services/account-service";
import { agentRunService, isAgentFeatureUnavailable } from "@/services/agent-run-service";
import { StatementImportWizard } from "@/components/vault/statement-import-wizard";
import { ReceiptUploadViewer } from "@/components/vault/receipt-upload-viewer";
import { formatDate } from "@/lib/utils";
import { toast } from "sonner";
import { useLocale, useTranslations } from "next-intl";
import { Receipt, FileText, Download, Trash2, ChevronLeft, ChevronRight, Sparkles, Loader2 } from "lucide-react";
import { cn } from "@/lib/utils";
import { useDocumentStatusLabel, useIngestionStatusLabel } from "@/lib/enum-labels";

export default function VaultPage() {
  return (
    <Suspense fallback={<p className="text-muted-foreground">Loading…</p>}>
      <VaultContent />
    </Suspense>
  );
}

function VaultContent() {
  const qc = useQueryClient();
  const t = useTranslations("vault");
  const tCommon = useTranslations("common");
  const locale = useLocale();
  const getDocumentStatusLabel = useDocumentStatusLabel();
  const getIngestionStatusLabel = useIngestionStatusLabel();
  const [page, setPage] = useState(0);
  const [activeTab, setActiveTab] = useState<"browse" | "import" | "upload">("browse");
  const [selectedAccountId, setSelectedAccountId] = useState<number | null>(null);

  const { data: vaultPage, isLoading } = useQuery({
    queryKey: ["vault", page],
    queryFn: () => vaultService.list(page, 20),
  });

  const { data: accounts } = useQuery({
    queryKey: ["accounts"],
    queryFn: () => accountService.list(),
  });

  const { error: agentRunsError } = useQuery({
    queryKey: ["agent-runs"],
    queryFn: () => agentRunService.list(),
    retry: false,
  });
  const agentFeatureUnavailable = isAgentFeatureUnavailable(agentRunsError);

  const startIngestionMut = useMutation({
    mutationFn: (documentId: string) => agentRunService.start(documentId),
    onSuccess: () => {
      toast.success(t("ingestion.toast.started"));
      qc.invalidateQueries({ queryKey: ["vault"] });
      qc.invalidateQueries({ queryKey: ["agent-runs"] });
    },
    onError: () => toast.error(t("ingestion.toast.startFailed")),
  });

  const deleteMut = useMutation({
    mutationFn: (id: string) => vaultService.deleteById(id),
    onSuccess: () => {
      toast.success(t("toast.documentDeleted"));
      qc.invalidateQueries({ queryKey: ["vault"] });
    },
    onError: () => toast.error(t("toast.deleteFailed")),
  });

  const openDownload = async (doc: VaultDocument) => {
    try {
      const url = await vaultService.getDownloadUrl(doc.id);
      const a = document.createElement("a");
      a.href = url;
      a.download = doc.originalFilename ?? doc.id;
      a.click();
      setTimeout(() => URL.revokeObjectURL(url), 10_000);
    } catch {
      toast.error(t("toast.downloadFailed"));
    }
  };

  const totalPages = vaultPage?.totalPages ?? 1;

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold tracking-tight text-foreground">{t("title")}</h1>
      </div>

      <div className="flex gap-1 border-b border-border">
        {(["browse", "import", "upload"] as const).map((tab) => (
          <button
            key={tab}
            onClick={() => setActiveTab(tab)}
            className={cn(
              "px-4 py-2 text-sm font-medium transition-colors",
              activeTab === tab
                ? "border-b-2 border-primary text-primary"
                : "text-muted-foreground hover:text-foreground"
            )}
          >
            {t(`tabs.${tab}`)}
          </button>
        ))}
      </div>

      {activeTab === "import" && (
        <div className="max-w-lg space-y-4">
          <div className="space-y-2">
            <label className="text-sm font-medium text-foreground">{t("account")}</label>
            <select
              className="w-full rounded-md border border-border bg-card px-3.5 py-2.5 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-primary/40"
              value={selectedAccountId ?? ""}
              onChange={(e) => setSelectedAccountId(Number(e.target.value) || null)}
            >
              <option value="">{t("selectAccount")}</option>
              {(accounts ?? []).map((a) => (
                <option key={a.id} value={a.id}>
                  {a.name}
                </option>
              ))}
            </select>
          </div>
          {selectedAccountId && (
            <StatementImportWizard
              accountId={selectedAccountId}
              onComplete={() => qc.invalidateQueries({ queryKey: ["vault"] })}
            />
          )}
        </div>
      )}

      {activeTab === "upload" && (
        <div className="max-w-md">
          <ReceiptUploadViewer
            onLinked={() => {
              toast.success(t("toast.receiptSaved"));
              qc.invalidateQueries({ queryKey: ["vault"] });
              setActiveTab("browse");
            }}
          />
        </div>
      )}

      {activeTab === "browse" && (
        <>
          {isLoading && (
            <p className="text-sm text-muted-foreground">{tCommon("loading")}</p>
          )}

          {!isLoading && (!vaultPage?.content?.length) && (
            <div className="flex flex-col items-center gap-3 py-12 text-center">
              <FileText className="h-10 w-10 text-muted-foreground/40" />
              <p className="text-sm text-muted-foreground">{t("emptyState.title")}</p>
              <p className="text-xs text-muted-foreground">
                {t("emptyState.body")}
              </p>
            </div>
          )}

          {(vaultPage?.content ?? []).length > 0 && (
            <div className="overflow-x-auto rounded-lg border border-border">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-border bg-muted/30">
                    <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("table.type")}</th>
                    <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("table.file")}</th>
                    <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("table.date")}</th>
                    <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("table.status")}</th>
                    <th className="px-4 py-3" />
                  </tr>
                </thead>
                <tbody className="divide-y divide-border">
                  {(vaultPage?.content ?? []).map((doc) => (
                    <tr key={doc.id} className="hover:bg-muted/20 transition-colors">
                      <td className="px-4 py-3">
                        {doc.type === "RECEIPT" ? (
                          <span className="flex items-center gap-1.5 text-amber-600 dark:text-amber-400">
                            <Receipt className="h-4 w-4" /> {t("receipt")}
                          </span>
                        ) : (
                          <span className="flex items-center gap-1.5 text-blue-600 dark:text-blue-400">
                            <FileText className="h-4 w-4" /> {t("statement")}
                          </span>
                        )}
                      </td>
                      <td className="px-4 py-3 max-w-[200px] truncate text-foreground">
                        {doc.originalFilename ?? doc.id}
                      </td>
                      <td className="px-4 py-3 text-muted-foreground">
                        {formatDate(doc.capturedAt.split("T")[0], locale)}
                      </td>
                      <td className="px-4 py-3">
                        <span className={cn(
                          "inline-flex rounded-full px-2 py-0.5 text-xs font-medium",
                          doc.status === "ACTIVE"
                            ? "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400"
                            : "bg-yellow-500/10 text-yellow-600 dark:text-yellow-400"
                        )}>
                          {getDocumentStatusLabel(doc.status as "STAGED" | "CONFIRMING" | "ACTIVE")}
                        </span>
                      </td>
                      <td className="px-4 py-3">
                        <div className="flex items-center justify-end gap-2">
                          {doc.type === "RECEIPT" && !agentFeatureUnavailable && (
                            <>
                              {doc.ingestionStatus ? (
                                <Link
                                  href="/dashboard/receipts"
                                  className="text-xs font-medium text-primary hover:underline"
                                >
                                  {t(`ingestion.viewRun`)} — {getIngestionStatusLabel(doc.ingestionStatus as "EXTRACTING" | "AWAITING_REVIEW" | "COMMITTED" | "REJECTED" | "FAILED")}
                                </Link>
                              ) : (
                                <button
                                  onClick={() => startIngestionMut.mutate(doc.id)}
                                  disabled={startIngestionMut.isPending}
                                  className="inline-flex items-center gap-1 text-xs font-medium text-primary hover:underline disabled:opacity-50"
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
                              )}
                            </>
                          )}
                          {doc.hasBinary && (
                            <button
                              onClick={() => openDownload(doc)}
                              className="inline-flex min-h-10 min-w-10 items-center justify-center text-muted-foreground hover:text-foreground transition-colors"
                              title={t("download")}
                              aria-label={t("downloadAria", { fileName: doc.originalFilename ?? t("downloadFallback") })}
                            >
                              <Download className="h-4 w-4" />
                            </button>
                          )}
                          <button
                            onClick={() => {
                              if (confirm(t("deleteConfirm"))) {
                                deleteMut.mutate(doc.id);
                              }
                            }}
                            className="inline-flex min-h-10 min-w-10 items-center justify-center text-muted-foreground hover:text-destructive transition-colors"
                            title={t("delete")}
                            aria-label={t("deleteAria", { fileName: doc.originalFilename ?? t("downloadFallback") })}
                          >
                            <Trash2 className="h-4 w-4" />
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {totalPages > 1 && (
            <div className="flex items-center justify-between">
              <button
                disabled={page === 0}
                onClick={() => setPage((p) => p - 1)}
                className="flex min-h-11 items-center gap-1 text-sm text-muted-foreground hover:text-foreground disabled:opacity-40 transition-colors"
              >
                <ChevronLeft className="h-4 w-4" /> {t("prev")}
              </button>
              <span className="text-sm text-muted-foreground">
                {t("pageOf", { current: page + 1, total: totalPages })}
              </span>
              <button
                disabled={page + 1 >= totalPages}
                onClick={() => setPage((p) => p + 1)}
                className="flex min-h-11 items-center gap-1 text-sm text-muted-foreground hover:text-foreground disabled:opacity-40 transition-colors"
              >
                {t("next")} <ChevronRight className="h-4 w-4" />
              </button>
            </div>
          )}
        </>
      )}
    </div>
  );
}
