"use client";

import { useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { vaultService, VaultDocument } from "@/services/vault-service";
import { toast } from "sonner";
import { Upload, X, Download, Receipt } from "lucide-react";
import { cn } from "@/lib/utils";

interface Props {
  transactionId?: number;
  /** If provided, pre-loads this vault document. */
  documentId?: string;
  onLinked?: (doc: VaultDocument) => void;
}

export function ReceiptUploadViewer({ transactionId, documentId, onLinked }: Props) {
  const qc = useQueryClient();
  const inputRef = useRef<HTMLInputElement>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);

  const { data: existing } = useQuery({
    queryKey: ["vault", documentId],
    queryFn: () => vaultService.getById(documentId!),
    enabled: !!documentId,
  });

  const upload = useMutation({
    mutationFn: (file: File) => vaultService.upload("RECEIPT", file),
    onSuccess: async (doc) => {
      toast.success("Receipt uploaded");
      if (transactionId) {
        const linked = await vaultService.linkToTransaction(doc.id, transactionId);
        onLinked?.(linked);
      } else {
        onLinked?.(doc);
      }
      qc.invalidateQueries({ queryKey: ["vault"] });
    },
    onError: () => toast.error("Upload failed"),
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
              {doc.originalFilename ?? "Receipt"}
            </p>
            <p className="text-xs text-muted-foreground">
              {new Date(doc.capturedAt).toLocaleDateString()}
            </p>
          </div>
          {doc.hasBinary && (
            <button
              onClick={openDownload}
              className="text-muted-foreground hover:text-foreground transition-colors"
              title="Download"
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
            {upload.isPending ? "Uploading…" : "Drop or click to attach receipt"}
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
            alt="Receipt preview"
            className="max-h-48 w-full rounded-lg object-contain border border-border"
          />
          <button
            onClick={() => setPreviewUrl(null)}
            className="absolute right-1 top-1 rounded-full bg-background/80 p-1 text-muted-foreground hover:text-foreground"
          >
            <X className="h-3 w-3" />
          </button>
        </div>
      )}
    </div>
  );
}
