"use client";

import { useRef } from "react";
import { useMutation } from "@tanstack/react-query";
import { vaultService } from "@/services/vault-service";
import { toast } from "sonner";
import { Upload, Loader2 } from "lucide-react";
import { useTranslations } from "next-intl";
import { cn } from "@/lib/utils";
import { useIdempotencyKey } from "@/lib/use-idempotency-key";
import { getIdempotencyErrorCode, isFileTooLargeError, isUnsupportedFormatError } from "@/lib/idempotency-error";

interface Props {
  accountId: number;
  onUploaded: () => void;
}

/** Uploads a statement file (stores the binary only — UPLOADED state, not parsed). */
export function StatementUploadDropzone({ accountId, onUploaded }: Props) {
  const t = useTranslations("vault.importWizard");
  const tCommon = useTranslations("common");
  const fileRef = useRef<HTMLInputElement>(null);
  const uploadIdempotency = useIdempotencyKey(null);

  const uploadMut = useMutation({
    mutationFn: (file: File) =>
      vaultService.importUpload(
        accountId,
        file,
        uploadIdempotency.resolve({ accountId, name: file.name, size: file.size, lastModified: file.lastModified })
      ),
    onSuccess: () => {
      uploadIdempotency.clear();
      toast.success(t("toast.uploaded"));
      onUploaded();
    },
    onError: (err) => {
      if (isFileTooLargeError(err)) {
        toast.error(t("toast.fileTooLarge"));
        return;
      }
      if (isUnsupportedFormatError(err)) {
        toast.error(t("toast.unsupportedFormat"));
        return;
      }
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

  return (
    <div
      className={cn(
        "flex flex-col items-center justify-center gap-3 rounded-lg border-2 border-dashed border-border p-8",
        "cursor-pointer hover:border-primary/50 hover:bg-primary/5 transition-colors",
        uploadMut.isPending && "pointer-events-none opacity-60"
      )}
      onClick={() => fileRef.current?.click()}
      onDragOver={(e) => e.preventDefault()}
      onDrop={(e) => {
        e.preventDefault();
        const file = e.dataTransfer.files[0];
        if (file) uploadMut.mutate(file);
      }}
    >
      {uploadMut.isPending ? (
        <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
      ) : (
        <Upload className="h-6 w-6 text-muted-foreground" />
      )}
      <div className="text-center">
        <p className="text-sm font-medium text-foreground">
          {uploadMut.isPending ? t("uploadingFile") : t("uploadPrompt")}
        </p>
        <p className="text-xs text-muted-foreground mt-1">{t("fileTypesHint")}</p>
      </div>
      <input
        ref={fileRef}
        type="file"
        accept=".csv,.ofx,.qfx"
        className="hidden"
        onChange={(e) => {
          const file = e.target.files?.[0];
          if (file) uploadMut.mutate(file);
        }}
      />
    </div>
  );
}
