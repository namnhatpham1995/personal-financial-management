"use client";

import { useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { vaultService, VaultDocument } from "@/services/vault-service";
import { toast } from "sonner";
import { Upload, X, Download, Receipt } from "lucide-react";
import { useTranslations } from "next-intl";
import { cn } from "@/lib/utils";
import { useIdempotencyKey } from "@/lib/use-idempotency-key";
import { getIdempotencyErrorCode } from "@/lib/idempotency-error";

interface Props {
  transactionId?: number;
  /** If provided, pre-loads this vault document. */
  documentId?: string;
  onLinked?: (doc: VaultDocument) => void;
}

export function ReceiptUploadViewer({ transactionId, documentId, onLinked }: Props) {
  const t = useTranslations("vault.uploadViewer");
  const qc = useQueryClient();
  const inputRef = useRef<HTMLInputElement>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);

  const { data: existing } = useQuery({
    queryKey: ["vault", documentId],
    queryFn: () => vaultService.getById(documentId!),
    enabled: !!documentId,
  });

  const tCommon = useTranslations("common");
  // File objects don't serialize meaningfully via JSON.stringify (they'd all collapse to
  // "{}"), so the idempotency payload is a stable fingerprint of file identity instead —
  // good enough to detect "this is the same upload attempt" vs. "a genuinely new file".
  const uploadIdempotency = useIdempotencyKey(null);

  const upload = useMutation({
    mutationFn: (file: File) =>
      vaultService.upload(
        "RECEIPT",
        file,
        uploadIdempotency.resolve({ name: file.name, size: file.size, lastModified: file.lastModified })
      ),
    onSuccess: async (doc) => {
      uploadIdempotency.clear();
      toast.success(t("toast.uploaded"));
      if (transactionId) {
        const linked = await vaultService.linkToTransaction(doc.id, transactionId);
        onLinked?.(linked);
      } else {
        onLinked?.(doc);
      }
      qc.invalidateQueries({ queryKey: ["vault"] });
    },
    onError: (err) => {
      const idempotencyCode = getIdempotencyErrorCode(err);
      if (idempotencyCode === "idempotency_key_conflict") {
        uploadIdempotency.clear();
        toast.error(t("toast.uploadFailed"));
      } else if (idempotencyCode === "operation_in_progress") {
        toast.error(tCommon("operationInProgress"));
      } else {
        toast.error(t("toast.uploadFailed"));
      }
    },
  });

  const handleFile = (file: File) => {
    if (file.type.startsWith("image/")) {
      const url = URL.createObjectURL(file);
      setPreviewUrl(url);
    }
    upload.mutate(file);
  };

  const openDownload = async () => {
    const id = existing?.id ?? documentId;
    if (!id) return;
    const url = await vaultService.getDownloadUrl(id);
    window.open(url, "_blank");
    setTimeout(() => URL.revokeObjectURL(url), 10_000);
  };

  const doc = existing;

  return (
    <div className="space-y-3">
      {doc ? (
        <div className="flex items-center gap-3 rounded-lg border border-border bg-card p-3">
          <Receipt className="h-5 w-5 shrink-0 text-success" />
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-medium text-foreground">
              {doc.originalFilename ?? t("receiptFallback")}
            </p>
            <p className="text-xs text-muted-foreground">
              {new Date(doc.capturedAt).toLocaleDateString()}
            </p>
          </div>
          {doc.hasBinary && (
            <button
              onClick={openDownload}
              className="inline-flex min-h-11 min-w-11 items-center justify-center text-muted-foreground hover:text-foreground transition-colors"
              title={t("download")}
              aria-label={t("downloadAria", { fileName: doc.originalFilename ?? t("downloadFallback") })}
            >
              <Download className="h-4 w-4" />
            </button>
          )}
        </div>
      ) : (
        <div
          className={cn(
            "flex flex-col items-center justify-center gap-2 rounded-lg border-2 border-dashed border-border p-6",
            "cursor-pointer hover:border-primary/50 hover:bg-primary/5 transition-colors",
            upload.isPending && "pointer-events-none opacity-60"
          )}
          onClick={() => inputRef.current?.click()}
          onDragOver={(e) => e.preventDefault()}
          onDrop={(e) => {
            e.preventDefault();
            const file = e.dataTransfer.files[0];
            if (file) handleFile(file);
          }}
        >
          <Upload className="h-6 w-6 text-muted-foreground" />
          <p className="text-sm text-muted-foreground">
            {upload.isPending ? t("uploading") : t("dropOrClick")}
          </p>
          <input
            ref={inputRef}
            type="file"
            accept="image/*,application/pdf"
            className="hidden"
            onChange={(e) => {
              const file = e.target.files?.[0];
              if (file) handleFile(file);
            }}
          />
        </div>
      )}

      {previewUrl && !doc && (
        <div className="relative">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img
            src={previewUrl}
            alt={t("previewAlt")}
            className="max-h-48 w-full rounded-lg object-contain border border-border"
          />
          <button
            onClick={() => setPreviewUrl(null)}
            className="absolute right-1 top-1 inline-flex min-h-9 min-w-9 items-center justify-center rounded-full bg-background/80 text-muted-foreground hover:text-foreground"
            aria-label={t("removeAria")}
          >
            <X className="h-3 w-3" />
          </button>
        </div>
      )}
    </div>
  );
}
