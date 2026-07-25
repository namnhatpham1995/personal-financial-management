"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { vaultService } from "@/services/vault-service";
import { accountService } from "@/services/account-service";
import { agentRunService, isAgentFeatureUnavailable } from "@/services/agent-run-service";
import { ReceiptUploadViewer } from "@/components/vault/receipt-upload-viewer";
import { DocumentReaderModal } from "@/components/vault/document-reader-modal";
import { formatDate } from "@/lib/utils";
import { useLocale, useTranslations } from "next-intl";
import Link from "next/link";
import { Receipt, Sparkles, Loader2 } from "lucide-react";
import { toast } from "sonner";
import { useIngestionStatusLabel } from "@/lib/enum-labels";

export function ReceiptTab() {
  const t = useTranslations("vault");
  const locale = useLocale();
  const qc = useQueryClient();
  const getIngestionStatusLabel = useIngestionStatusLabel();
  const [accountId, setAccountId] = useState<number | null>(null);
  const [readingDoc, setReadingDoc] = useState<{ id: string; filename?: string } | null>(null);

  const { data: accounts } = useQuery({ queryKey: ["accounts"], queryFn: () => accountService.list() });

  const listQueryKey = ["vault-by-account", accountId, "RECEIPT"] as const;
  const listQuery = useQuery({
    queryKey: listQueryKey,
    queryFn: () => vaultService.listByAccount(accountId!, "RECEIPT", 0, 100),
    enabled: !!accountId,
  });

  const { error: agentRunsError } = useQuery({
    queryKey: ["agent-runs"],
    queryFn: () => agentRunService.list(),
    retry: false,
  });
  const agentFeatureUnavailable = isAgentFeatureUnavailable(agentRunsError);

  const invalidateList = () => qc.invalidateQueries({ queryKey: listQueryKey });

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

  return (
    <div className="space-y-4">
      <div className="max-w-sm space-y-2">
        <label className="text-sm font-medium text-foreground">{t("account")}</label>
        <select
          className="w-full rounded-md border border-border bg-card px-3.5 py-2.5 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-primary/40"
          value={accountId ?? ""}
          onChange={(e) => setAccountId(Number(e.target.value) || null)}
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
        <div className="max-w-lg space-y-4">
          <ReceiptUploadViewer accountId={accountId} onLinked={invalidateList} />

          {receipts.length > 0 && (
            <div className="rounded-lg border border-border divide-y divide-border">
              {receipts.map((doc) => (
                <div key={doc.id} className="flex items-center gap-3 px-3 py-2">
                  <Receipt className="h-4 w-4 shrink-0 text-amber-600 dark:text-amber-400" />
                  <button
                    onClick={() => setReadingDoc({ id: doc.id, filename: doc.originalFilename })}
                    className="min-w-0 flex-1 truncate text-left text-sm text-foreground hover:underline"
                  >
                    {doc.originalFilename ?? doc.id}
                  </button>
                  <span className="shrink-0 text-xs text-muted-foreground">
                    {formatDate(doc.capturedAt.split("T")[0], locale)}
                  </span>
                  {!agentFeatureUnavailable &&
                    (doc.ingestionStatus ? (
                      <Link
                        href="/dashboard/receipts"
                        className="shrink-0 text-xs font-medium text-primary hover:underline"
                      >
                        {t("ingestion.viewRun")} —{" "}
                        {getIngestionStatusLabel(
                          doc.ingestionStatus as "EXTRACTING" | "AWAITING_REVIEW" | "COMMITTED" | "REJECTED" | "FAILED"
                        )}
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
                </div>
              ))}
            </div>
          )}
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
