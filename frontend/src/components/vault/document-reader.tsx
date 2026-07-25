"use client";

import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { vaultService } from "@/services/vault-service";
import { Loader2, TriangleAlert } from "lucide-react";
import { useTranslations } from "next-intl";
import { Button } from "@/components/ui/button";

interface Props {
  documentId: string;
  originalFilename?: string;
}

/** Renders a stored vault binary inline: images, PDFs (embed + download fallback), and text (CSV/OFX) as <pre>. */
export function DocumentReader({ documentId, originalFilename }: Props) {
  const t = useTranslations("vault.documentReader");
  const { data, isLoading, isError, refetch } = useQuery({
    queryKey: ["vault-inline-content", documentId],
    queryFn: () => vaultService.getInlineContent(documentId),
  });

  const [objectUrl, setObjectUrl] = useState<string | null>(null);
  const [textContent, setTextContent] = useState<string | null>(null);

  useEffect(() => {
    if (!data) return;
    const { blob, contentType } = data;
    if (contentType.startsWith("image/") || contentType === "application/pdf") {
      const url = URL.createObjectURL(blob);
      setObjectUrl(url);
      return () => URL.revokeObjectURL(url);
    }
    let cancelled = false;
    blob.text().then((text) => {
      if (!cancelled) setTextContent(text);
    });
    return () => {
      cancelled = true;
    };
  }, [data]);

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-8">
        <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
      </div>
    );
  }

  if (isError || !data) {
    return (
      <div className="flex flex-col items-center gap-2 py-8 text-center">
        <TriangleAlert className="h-6 w-6 text-destructive" />
        <p className="text-sm text-foreground">{t("loadFailed")}</p>
        <Button size="sm" variant="secondary" onClick={() => refetch()}>
          {t("retry")}
        </Button>
      </div>
    );
  }

  const { contentType } = data;

  if (contentType.startsWith("image/")) {
    return objectUrl ? (
      // eslint-disable-next-line @next/next/no-img-element
      <img
        src={objectUrl}
        alt={originalFilename ?? t("imageAlt")}
        className="max-h-[70vh] w-full rounded-lg border border-border object-contain"
      />
    ) : null;
  }

  if (contentType === "application/pdf") {
    return objectUrl ? (
      <div className="space-y-2">
        <embed src={objectUrl} type="application/pdf" className="h-[70vh] w-full rounded-lg border border-border" />
        <a
          href={objectUrl}
          download={originalFilename ?? "document.pdf"}
          className="text-xs text-primary hover:underline"
        >
          {t("downloadFallback")}
        </a>
      </div>
    ) : null;
  }

  return (
    <pre className="max-h-[70vh] overflow-auto whitespace-pre-wrap rounded-lg border border-border bg-muted/20 p-3 text-xs text-foreground">
      {textContent ?? ""}
    </pre>
  );
}
