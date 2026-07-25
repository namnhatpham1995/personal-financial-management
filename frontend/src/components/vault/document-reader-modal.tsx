"use client";

import * as Dialog from "@radix-ui/react-dialog";
import { X } from "lucide-react";
import { useTranslations } from "next-intl";
import { DocumentReader } from "@/components/vault/document-reader";

interface Props {
  /** Document to read, or null to keep the modal closed. */
  documentId: string | null;
  originalFilename?: string;
  onClose: () => void;
}

export function DocumentReaderModal({ documentId, originalFilename, onClose }: Props) {
  const t = useTranslations("vault.documentReader");

  return (
    <Dialog.Root
      open={!!documentId}
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
    >
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-50 bg-black/50" />
        <Dialog.Content className="fixed left-1/2 top-1/2 z-50 w-[90vw] max-w-2xl -translate-x-1/2 -translate-y-1/2 rounded-lg border border-border bg-card p-4 shadow-lg">
          <div className="mb-3 flex items-center justify-between gap-3">
            <Dialog.Title className="truncate text-sm font-medium text-foreground">
              {originalFilename ?? t("title")}
            </Dialog.Title>
            <Dialog.Close asChild>
              <button
                aria-label={t("close")}
                className="shrink-0 text-muted-foreground hover:text-foreground transition-colors"
              >
                <X className="h-4 w-4" />
              </button>
            </Dialog.Close>
          </div>
          {documentId && <DocumentReader documentId={documentId} originalFilename={originalFilename} />}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
