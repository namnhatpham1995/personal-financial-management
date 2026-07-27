"use client";

import { useState } from "react";
import * as Dialog from "@radix-ui/react-dialog";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { Loader2, MoveRight, X } from "lucide-react";
import { toast } from "sonner";
import type { Account } from "@/services/account-service";
import { vaultService, type VaultDocument } from "@/services/vault-service";
import { useIdempotencyKey } from "@/lib/use-idempotency-key";
import { getIdempotencyErrorCode } from "@/lib/idempotency-error";

interface Props {
  document: VaultDocument;
  accounts: Account[];
  onSuccess?: () => void;
}

export function VaultReassignmentDialog({ document, accounts, onSuccess }: Props) {
  const t = useTranslations("vault.reassignment");
  const tCommon = useTranslations("common");
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [targetAccountId, setTargetAccountId] = useState<number | null>(null);
  const idempotency = useIdempotencyKey(null);

  const preview = useQuery({
    queryKey: ["vault-reassignment-preview", document.id, targetAccountId],
    queryFn: () => vaultService.previewReassignment(document.id, targetAccountId!),
    enabled: open && targetAccountId !== null,
    retry: false,
  });

  const mutation = useMutation({
    mutationFn: () =>
      vaultService.reassign(
        document.id,
        targetAccountId!,
        idempotency.resolve({ documentId: document.id, targetAccountId })
      ),
    onSuccess: async () => {
      idempotency.clear();
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["vault-by-type"] }),
        queryClient.invalidateQueries({ queryKey: ["vault-by-account"] }),
        queryClient.invalidateQueries({ queryKey: ["vault"] }),
        queryClient.invalidateQueries({ queryKey: ["agent-runs"] }),
        queryClient.invalidateQueries({ queryKey: ["transactions"] }),
        queryClient.invalidateQueries({ queryKey: ["accounts"] }),
        queryClient.invalidateQueries({ queryKey: ["analytics"] }),
      ]);
      toast.success(t("toast.success"));
      setOpen(false);
      onSuccess?.();
    },
    onError: (error) => {
      const code = getIdempotencyErrorCode(error);
      if (code === "operation_in_progress") {
        toast.error(tCommon("operationInProgress"));
      } else {
        toast.error(t("toast.failed"));
      }
    },
  });

  const availableTargets = accounts.filter((account) => account.id !== document.accountId);

  return (
    <Dialog.Root
      open={open}
      onOpenChange={(nextOpen) => {
        setOpen(nextOpen);
        if (nextOpen) setTargetAccountId(null);
      }}
    >
      <Dialog.Trigger asChild>
        <button
          type="button"
          className="inline-flex min-h-9 items-center gap-1.5 rounded-full border border-border px-3 py-1.5 text-xs font-medium text-foreground hover:bg-hover-surface focus:outline-none focus:ring-2 focus:ring-primary/40"
        >
          <MoveRight className="h-3.5 w-3.5" aria-hidden="true" />
          {t("open")}
        </button>
      </Dialog.Trigger>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-50 bg-black/50" />
        <Dialog.Content className="fixed left-1/2 top-1/2 z-50 w-[min(92vw,32rem)] -translate-x-1/2 -translate-y-1/2 rounded-lg border border-border bg-card p-5 shadow-lg">
          <div className="flex items-start justify-between gap-4">
            <div>
              <Dialog.Title className="text-lg font-semibold text-foreground">{t("title")}</Dialog.Title>
              <Dialog.Description className="mt-1 text-sm text-muted-foreground">
                {t("description", { fileName: document.originalFilename ?? document.id })}
              </Dialog.Description>
            </div>
            <Dialog.Close asChild>
              <button type="button" className="rounded-full p-1 text-muted-foreground hover:bg-hover-surface" aria-label={t("close")}>
                <X className="h-4 w-4" />
              </button>
            </Dialog.Close>
          </div>

          <div className="mt-5 space-y-4">
            <label className="block space-y-2 text-sm font-medium text-foreground">
              <span>{t("targetAccount")}</span>
              <select
                className="w-full rounded-md border border-border bg-card px-3.5 py-2.5 font-normal text-foreground focus:outline-none focus:ring-2 focus:ring-primary/40"
                value={targetAccountId ?? ""}
                onChange={(event) => setTargetAccountId(Number(event.target.value) || null)}
              >
                <option value="">{t("selectTarget")}</option>
                {availableTargets.map((account) => (
                  <option key={account.id} value={account.id}>
                    {account.name} - {account.currency}
                  </option>
                ))}
              </select>
            </label>

            {preview.isLoading && <p className="text-sm text-muted-foreground">{t("loading")}</p>}
            {preview.isError && <p className="text-sm text-destructive">{t("previewFailed")}</p>}
            {preview.data && (
              <div className="space-y-2 rounded-md border border-border bg-muted/20 p-3 text-sm">
                <p className="text-foreground">
                  {t("imports", { count: preview.data.importedTransactionCount })}
                </p>
                {preview.data.detachManualLink && <p className="text-muted-foreground">{t("detachLink")}</p>}
                {preview.data.currencyChanged && (
                  <p className="text-warning">
                    {t("currencyWarning", {
                      from: preview.data.sourceCurrency ?? "-",
                      to: preview.data.targetCurrency,
                    })}
                  </p>
                )}
                <p className="text-muted-foreground">{t("rerunRequired")}</p>
              </div>
            )}

            <div className="flex justify-end gap-2">
              <Dialog.Close asChild>
                <button type="button" className="rounded-full border border-border px-4 py-2 text-sm font-medium text-foreground hover:bg-hover-surface">
                  {t("cancel")}
                </button>
              </Dialog.Close>
              <button
                type="button"
                disabled={!preview.data || mutation.isPending}
                onClick={() => mutation.mutate()}
                className="inline-flex items-center gap-2 rounded-full bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
              >
                {mutation.isPending && <Loader2 className="h-4 w-4 animate-spin" />}
                {mutation.isPending ? t("moving") : t("confirm")}
              </button>
            </div>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
