"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle, Loader2, Receipt } from "lucide-react";
import { toast } from "sonner";
import { useLocale, useTranslations } from "next-intl";
import type { Account } from "@/services/account-service";
import { vaultService } from "@/services/vault-service";
import { formatDate } from "@/lib/utils";

interface Props {
  accounts: Account[];
  onRead: (document: { id: string; filename?: string }) => void;
}

export function LegacyReceiptRecovery({ accounts, onRead }: Props) {
  const t = useTranslations("vault.unassignedReceipts");
  const locale = useLocale();
  const queryClient = useQueryClient();
  const [accountByDocument, setAccountByDocument] = useState<Record<string, number | undefined>>({});

  const unassignedQuery = useQuery({
    queryKey: ["vault", "unassigned-receipts"],
    queryFn: () => vaultService.listUnassignedReceipts(0, 100),
  });

  const assignMutation = useMutation({
    mutationFn: ({ documentId, accountId }: { documentId: string; accountId: number }) =>
      vaultService.assignAccount(documentId, accountId),
    onSuccess: async () => {
      toast.success(t("assigned"));
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["vault", "unassigned-receipts"] }),
        queryClient.invalidateQueries({ queryKey: ["vault-by-account"] }),
      ]);
    },
    onError: () => toast.error(t("assignFailed")),
  });

  if (unassignedQuery.isLoading) {
    return (
      <div className="flex items-center gap-2 rounded-lg border border-border bg-card p-4 text-sm text-muted-foreground shadow-card">
        <Loader2 className="h-4 w-4 animate-spin" />
        {t("loading")}
      </div>
    );
  }

  if (unassignedQuery.isError) {
    return (
      <div className="flex items-center justify-between gap-3 rounded-lg border border-destructive/30 bg-card p-4 shadow-card">
        <span className="text-sm text-destructive">{t("loadFailed")}</span>
        <button
          type="button"
          onClick={() => unassignedQuery.refetch()}
          className="rounded-full border border-border px-3 py-1.5 text-sm font-medium text-foreground hover:bg-hover-surface focus:outline-none focus:ring-2 focus:ring-primary/40"
        >
          {t("retry")}
        </button>
      </div>
    );
  }

  const receipts = unassignedQuery.data?.content ?? [];
  if (receipts.length === 0) {
    return null;
  }

  return (
    <section className="space-y-3 rounded-lg border border-warning/40 bg-card p-4 shadow-card">
      <div className="flex gap-3">
        <AlertTriangle className="mt-0.5 h-5 w-5 shrink-0 text-warning" aria-hidden="true" />
        <div>
          <h3 className="font-medium text-foreground">{t("title")}</h3>
          <p className="mt-1 text-sm text-muted-foreground">{t("body")}</p>
        </div>
      </div>

      <div className="divide-y divide-border rounded-md border border-border">
        {receipts.map((document) => {
          const fileName = document.originalFilename ?? document.id;
          const selectedAccountId = accountByDocument[document.id];
          const isAssigning =
            assignMutation.isPending && assignMutation.variables?.documentId === document.id;

          return (
            <div key={document.id} className="space-y-3 p-3">
              <div className="flex items-center gap-3">
                <Receipt className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden="true" />
                <button
                  type="button"
                  onClick={() => onRead({ id: document.id, filename: document.originalFilename })}
                  className="min-w-0 flex-1 truncate text-left text-sm text-foreground hover:underline focus:outline-none focus:ring-2 focus:ring-primary/40"
                >
                  {fileName}
                </button>
                <span className="shrink-0 text-xs text-muted-foreground">
                  {formatDate(document.capturedAt.split("T")[0], locale)}
                </span>
              </div>

              {accounts.length === 0 ? (
                <p className="text-sm text-muted-foreground">{t("noAccounts")}</p>
              ) : (
                <div className="flex flex-col gap-2 sm:flex-row">
                  <select
                    aria-label={t("accountAria", { fileName })}
                    value={selectedAccountId ?? ""}
                    onChange={(event) =>
                      setAccountByDocument((current) => ({
                        ...current,
                        [document.id]: Number(event.target.value) || undefined,
                      }))
                    }
                    className="min-w-0 flex-1 rounded-md border border-border bg-card px-3.5 py-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-primary/40"
                  >
                    <option value="">{t("selectAccount")}</option>
                    {accounts.map((account) => (
                      <option key={account.id} value={account.id}>
                        {account.name}
                      </option>
                    ))}
                  </select>
                  <button
                    type="button"
                    disabled={!selectedAccountId || isAssigning}
                    onClick={() =>
                      selectedAccountId &&
                      assignMutation.mutate({ documentId: document.id, accountId: selectedAccountId })
                    }
                    className="inline-flex items-center justify-center gap-2 rounded-full bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 focus:outline-none focus:ring-2 focus:ring-primary/40 disabled:opacity-50"
                  >
                    {isAssigning && <Loader2 className="h-4 w-4 animate-spin" />}
                    {isAssigning ? t("assigning") : t("assign")}
                  </button>
                </div>
              )}
            </div>
          );
        })}
      </div>
    </section>
  );
}
