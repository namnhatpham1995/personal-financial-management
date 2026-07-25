"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { vaultService } from "@/services/vault-service";
import { accountService } from "@/services/account-service";
import { StatementUploadDropzone } from "@/components/vault/statement-upload-dropzone";
import { StatementImportWizard } from "@/components/vault/statement-import-wizard";
import { formatDate, cn } from "@/lib/utils";
import { useLocale, useTranslations } from "next-intl";
import { toast } from "sonner";
import { Trash2, ChevronDown, ChevronRight, FileText } from "lucide-react";

type SubTab = "upload" | "import";

export function StatementTab() {
  const t = useTranslations("vault");
  const locale = useLocale();
  const qc = useQueryClient();
  const [accountId, setAccountId] = useState<number | null>(null);
  const [subTab, setSubTab] = useState<SubTab>("upload");
  const [reviewingId, setReviewingId] = useState<string | null>(null);
  const [importedExpanded, setImportedExpanded] = useState(false);

  const { data: accounts } = useQuery({ queryKey: ["accounts"], queryFn: () => accountService.list() });

  const listQueryKey = ["vault-by-account", accountId, "STATEMENT"] as const;
  const listQuery = useQuery({
    queryKey: listQueryKey,
    queryFn: () => vaultService.listByAccount(accountId!, "STATEMENT", 0, 100),
    enabled: !!accountId,
  });

  const docs = listQuery.data?.content ?? [];
  const uploaded = docs.filter((d) => d.status === "UPLOADED");
  const staged = docs.filter((d) => d.status === "STAGED");
  const active = docs.filter((d) => d.status === "ACTIVE");

  const invalidateList = () => qc.invalidateQueries({ queryKey: listQueryKey });

  const deleteMut = useMutation({
    mutationFn: (id: string) => vaultService.deleteById(id),
    onSuccess: () => {
      toast.success(t("toast.documentDeleted"));
      invalidateList();
    },
    onError: () => toast.error(t("toast.deleteFailed")),
  });

  return (
    <div className="space-y-4">
      <div className="max-w-sm space-y-2">
        <label className="text-sm font-medium text-foreground">{t("account")}</label>
        <select
          className="w-full rounded-md border border-border bg-card px-3.5 py-2.5 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-primary/40"
          value={accountId ?? ""}
          onChange={(e) => {
            setAccountId(Number(e.target.value) || null);
            setReviewingId(null);
          }}
        >
          <option value="">{t("selectAccount")}</option>
          {(accounts ?? []).map((a) => (
            <option key={a.id} value={a.id}>
              {a.name}
            </option>
          ))}
        </select>
      </div>

      {accountId && (
        <>
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
                  <span className="ml-1.5 rounded-full bg-primary/10 px-1.5 py-0.5 text-xs text-primary">
                    {staged.length}
                  </span>
                )}
              </button>
            ))}
          </div>

          {subTab === "upload" && (
            <div className="max-w-lg space-y-4">
              <StatementUploadDropzone accountId={accountId} onUploaded={invalidateList} />
              {uploaded.length > 0 && (
                <div className="rounded-lg border border-border divide-y divide-border">
                  {uploaded.map((doc) => (
                    <div key={doc.id} className="flex items-center gap-3 px-3 py-2">
                      <FileText className="h-4 w-4 shrink-0 text-blue-600 dark:text-blue-400" />
                      <span className="min-w-0 flex-1 truncate text-sm text-foreground">
                        {doc.originalFilename ?? doc.id}
                      </span>
                      <span className="shrink-0 text-xs text-muted-foreground">
                        {formatDate(doc.capturedAt.split("T")[0], locale)}
                      </span>
                      <button
                        onClick={() => {
                          setSubTab("import");
                          setReviewingId(doc.id);
                        }}
                        className="shrink-0 text-xs font-medium text-primary hover:underline"
                      >
                        {t("startImport")}
                      </button>
                      <button
                        onClick={() => {
                          if (confirm(t("deleteConfirm"))) deleteMut.mutate(doc.id);
                        }}
                        className="inline-flex min-h-9 min-w-9 shrink-0 items-center justify-center text-muted-foreground hover:text-destructive transition-colors"
                        title={t("delete")}
                        aria-label={t("deleteAria", { fileName: doc.originalFilename ?? t("downloadFallback") })}
                      >
                        <Trash2 className="h-4 w-4" />
                      </button>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}

          {subTab === "import" && (
            <div className="max-w-lg space-y-4">
              {reviewingId ? (
                <div className="space-y-3">
                  <button
                    onClick={() => setReviewingId(null)}
                    className="text-xs text-muted-foreground hover:text-foreground transition-colors"
                  >
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
                      <div className="rounded-lg border border-border divide-y divide-border">
                        {staged.map((doc) => (
                          <button
                            key={doc.id}
                            onClick={() => setReviewingId(doc.id)}
                            className="flex w-full items-center gap-3 px-3 py-2 text-left transition-colors hover:bg-muted/50"
                          >
                            <FileText className="h-4 w-4 shrink-0 text-yellow-600 dark:text-yellow-400" />
                            <span className="min-w-0 flex-1 truncate text-sm text-foreground">
                              {doc.originalFilename ?? doc.id}
                            </span>
                            <span className="shrink-0 text-xs text-muted-foreground">
                              {formatDate(doc.capturedAt.split("T")[0], locale)}
                            </span>
                          </button>
                        ))}
                      </div>
                    )}
                  </div>

                  {active.length > 0 && (
                    <div>
                      <button
                        onClick={() => setImportedExpanded((v) => !v)}
                        className="mb-2 flex items-center gap-1 text-sm font-semibold text-foreground"
                      >
                        {importedExpanded ? (
                          <ChevronDown className="h-4 w-4" />
                        ) : (
                          <ChevronRight className="h-4 w-4" />
                        )}
                        {t("importedBucket", { count: active.length })}
                      </button>
                      {importedExpanded && (
                        <div className="rounded-lg border border-border divide-y divide-border">
                          {active.map((doc) => (
                            <div key={doc.id} className="flex items-center gap-3 px-3 py-2">
                              <FileText className="h-4 w-4 shrink-0 text-emerald-600 dark:text-emerald-400" />
                              <span className="min-w-0 flex-1 truncate text-sm text-foreground">
                                {doc.originalFilename ?? doc.id}
                              </span>
                              <span className="shrink-0 text-xs text-muted-foreground">
                                {formatDate(doc.capturedAt.split("T")[0], locale)}
                              </span>
                            </div>
                          ))}
                        </div>
                      )}
                    </div>
                  )}
                </>
              )}
            </div>
          )}
        </>
      )}
    </div>
  );
}
