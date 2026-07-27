"use client";

import { useState, type ReactNode } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { vaultService } from "@/services/vault-service";
import { accountService } from "@/services/account-service";
import { StatementUploadDropzone } from "@/components/vault/statement-upload-dropzone";
import { StatementImportWizard } from "@/components/vault/statement-import-wizard";
import { DocumentReaderModal } from "@/components/vault/document-reader-modal";
import { VaultAccountFilter } from "@/components/vault/vault-account-filter";
import { VaultReassignmentDialog } from "@/components/vault/vault-reassignment-dialog";
import { formatDate, cn } from "@/lib/utils";
import { useLocale, useTranslations } from "next-intl";
import { toast } from "sonner";
import { Trash2, ChevronDown, ChevronLeft, ChevronRight, FileText } from "lucide-react";

type SubTab = "upload" | "import";

export function StatementTab() {
  const t = useTranslations("vault");
  const tCommon = useTranslations("common");
  const locale = useLocale();
  const qc = useQueryClient();
  const [accountId, setAccountId] = useState<number | null>(null);
  const [uploadAccountId, setUploadAccountId] = useState<number | null>(null);
  const [subTab, setSubTab] = useState<SubTab>("upload");
  const [page, setPage] = useState(0);
  const [reviewingId, setReviewingId] = useState<string | null>(null);
  const [importedExpanded, setImportedExpanded] = useState(false);
  const [readingDoc, setReadingDoc] = useState<{ id: string; filename?: string } | null>(null);

  const { data: accounts } = useQuery({ queryKey: ["accounts"], queryFn: () => accountService.list() });
  const accountList = accounts ?? [];
  const listQueryKey = ["vault-by-type", "STATEMENT", ...(accountId == null ? [] : [accountId]), page] as const;
  const listQuery = useQuery({
    queryKey: listQueryKey,
    queryFn: () => vaultService.listByType("STATEMENT", accountId ?? undefined, page, 100),
  });

  const docs = listQuery.data?.content ?? [];
  const uploaded = docs.filter((d) => d.status === "UPLOADED");
  const staged = docs.filter((d) => d.status === "STAGED" || d.status === "CONFIRMING");
  const active = docs.filter((d) => d.status === "ACTIVE");
  const accountName = (id?: number) =>
    id == null ? t("unassigned") : accountList.find((account) => account.id === id)?.name ?? t("unassigned");

  const invalidateList = () => qc.invalidateQueries({ queryKey: ["vault-by-type", "STATEMENT"] });

  const deleteMut = useMutation({
    mutationFn: (id: string) => vaultService.deleteById(id),
    onSuccess: () => {
      toast.success(t("toast.documentDeleted"));
      invalidateList();
    },
    onError: () => toast.error(t("toast.deleteFailed")),
  });

  const renderDocument = (doc: (typeof docs)[number], statusClass: string, action?: ReactNode) => (
    <div key={doc.id} className="flex items-center gap-3 px-3 py-2">
      <FileText className={cn("h-4 w-4 shrink-0", statusClass)} />
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
      {action}
      <VaultReassignmentDialog document={doc} accounts={accountList} onSuccess={invalidateList} />
      <button
        onClick={() => {
          if (confirm(t("deleteConfirm"))) deleteMut.mutate(doc.id);
        }}
        className="inline-flex min-h-9 min-w-9 shrink-0 items-center justify-center text-muted-foreground transition-colors hover:text-destructive"
        title={t("delete")}
        aria-label={t("deleteAria", { fileName: doc.originalFilename ?? t("downloadFallback") })}
      >
        <Trash2 className="h-4 w-4" />
      </button>
    </div>
  );

  return (
    <div className="space-y-4">
      <VaultAccountFilter
        accounts={accountList}
        accountId={accountId}
        onChange={(nextAccountId) => {
          setAccountId(nextAccountId);
          setUploadAccountId(nextAccountId);
          setPage(0);
          setReviewingId(null);
        }}
      />
      {listQuery.isLoading && <p className="text-sm text-muted-foreground">{tCommon("loading")}</p>}
      {listQuery.isError && <p className="text-sm text-destructive">{t("loadFailed")}</p>}

      <div className="flex gap-1 border-b border-border">
        {(["upload", "import"] as const).map((tab) => (
          <button
            key={tab}
            onClick={() => {
              setSubTab(tab);
              setReviewingId(null);
            }}
            className={cn(
              "px-3 py-2 text-sm font-medium transition-colors",
              subTab === tab
                ? "border-b-2 border-primary text-primary"
                : "text-muted-foreground hover:text-foreground"
            )}
          >
            {t(`statementSubTabs.${tab}`)}
            {tab === "import" && staged.length > 0 && (
              <span className="ml-1.5 rounded-full bg-primary/10 px-1.5 py-0.5 text-xs text-primary">{staged.length}</span>
            )}
          </button>
        ))}
      </div>

      {subTab === "upload" && (
        <div className="max-w-lg space-y-4">
          <div className="space-y-2">
            <label className="text-sm font-medium text-foreground" htmlFor="statement-upload-account">
              {t("uploadAccount")}
            </label>
            <select
              id="statement-upload-account"
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
            <StatementUploadDropzone accountId={uploadAccountId} onUploaded={invalidateList} />
          ) : (
            <p className="text-sm text-muted-foreground">{t("selectAccountToUpload")}</p>
          )}

          <div>
            <h3 className="mb-2 text-sm font-semibold text-foreground">{t("availableFiles")}</h3>
            {uploaded.length === 0 ? (
              <p className="text-sm text-muted-foreground">{t("noUploaded")}</p>
            ) : (
              <div className="divide-y divide-border rounded-lg border border-border">
                {uploaded.map((doc) => renderDocument(doc, "text-blue-600 dark:text-blue-400", (
                  <button
                    onClick={() => {
                      setSubTab("import");
                      setReviewingId(doc.id);
                    }}
                    className="shrink-0 text-xs font-medium text-primary hover:underline"
                  >
                    {t("startImport")}
                  </button>
                )))}
              </div>
            )}
          </div>
        </div>
      )}

      {subTab === "import" && (
        <div className="max-w-lg space-y-4">
          {reviewingId ? (
            <div className="space-y-3">
              <button onClick={() => setReviewingId(null)} className="text-xs text-muted-foreground transition-colors hover:text-foreground">
                {t("backToList")}
              </button>
              <StatementImportWizard
                documentId={reviewingId}
                onComplete={() => {
                  invalidateList();
                  setReviewingId(null);
                }}
              />
            </div>
          ) : (
            <>
              <div>
                <h3 className="mb-2 text-sm font-semibold text-foreground">{t("unimported")}</h3>
                {staged.length === 0 ? (
                  <p className="text-sm text-muted-foreground">{t("noUnimported")}</p>
                ) : (
                  <div className="divide-y divide-border rounded-lg border border-border">
                    {staged.map((doc) => (
                      <div key={doc.id} className="flex items-center gap-3 px-3 py-2">
                        <button onClick={() => setReviewingId(doc.id)} className="flex min-w-0 flex-1 items-center gap-3 text-left transition-colors hover:bg-muted/50">
                          <FileText className="h-4 w-4 shrink-0 text-yellow-600 dark:text-yellow-400" />
                          <span className="min-w-0 flex-1 truncate text-sm text-foreground">{doc.originalFilename ?? doc.id}</span>
                          <span className="hidden shrink-0 text-xs text-muted-foreground sm:inline">{accountName(doc.accountId)}</span>
                          <span className="shrink-0 text-xs text-muted-foreground">{formatDate(doc.capturedAt.split("T")[0], locale)}</span>
                        </button>
                        <VaultReassignmentDialog document={doc} accounts={accountList} onSuccess={invalidateList} />
                      </div>
                    ))}
                  </div>
                )}
              </div>

              {active.length > 0 && (
                <div>
                  <button onClick={() => setImportedExpanded((value) => !value)} className="mb-2 flex items-center gap-1 text-sm font-semibold text-foreground">
                    {importedExpanded ? <ChevronDown className="h-4 w-4" /> : <ChevronRight className="h-4 w-4" />}
                    {t("importedBucket", { count: active.length })}
                  </button>
                  {importedExpanded && (
                    <div className="divide-y divide-border rounded-lg border border-border">
                      {active.map((doc) => renderDocument(doc, "text-emerald-600 dark:text-emerald-400"))}
                    </div>
                  )}
                </div>
              )}
            </>
          )}
        </div>
      )}

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
