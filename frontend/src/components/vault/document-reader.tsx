"use client";

import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { vaultService } from "@/services/vault-service";
import { Loader2, TriangleAlert } from "lucide-react";
import { useTranslations } from "next-intl";
import { Button } from "@/components/ui/button";
import { useTransactionTypeLabel } from "@/lib/enum-labels";

interface Props {
  documentId: string;
  originalFilename?: string;
}

/**
 * Renders a stored vault binary inline: images, PDFs (embed + download fallback), and
 * CSV/OFX statements as a table of previously parsed rows (falling back to raw text when
 * the statement has never been parsed).
 */
export function DocumentReader({ documentId, originalFilename }: Props) {
  const t = useTranslations("vault.documentReader");
  const getTypeLabel = useTransactionTypeLabel();
  const { data, isLoading, isError, refetch } = useQuery({
    queryKey: ["vault-inline-content", documentId],
    queryFn: () => vaultService.getInlineContent(documentId),
  });

  const isStatementContent = !!data && !data.contentType.startsWith("image/") && data.contentType !== "application/pdf";
  const previewRows = useQuery({
    queryKey: ["vault-preview-rows", documentId],
    queryFn: () => vaultService.getPreviewRows(documentId),
    enabled: isStatementContent,
    // A 404 (statement never parsed) is an expected outcome that falls back to raw text,
    // not a transient failure worth react-query's default 3x retry.
    retry: false,
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
        {/* #view=FitH nudges the browser's native PDF viewer toward a fit-width default
            zoom instead of an autofit-shrunk thumbnail scale (informal PDF open-parameters
            convention; not universally honored, but harmless where it isn't). */}
        <embed
          src={`${objectUrl}#view=FitH`}
          type="application/pdf"
          className="h-[80vh] w-full rounded-lg border border-border"
        />
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

  if (previewRows.isLoading) {
    return (
      <div className="flex items-center justify-center py-8">
        <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
      </div>
    );
  }

  if (previewRows.data) {
    const rows = previewRows.data;
    const headers = [
      t("table.dateHeader"),
      t("table.descriptionHeader"),
      t("table.categoryHeader"),
      t("table.typeHeader"),
      t("table.amountHeader"),
    ];
    return (
      <div className="max-h-[70vh] overflow-auto rounded-lg border border-border bg-card">
        <table className="w-full text-sm">
          <thead className="border-b border-border">
            <tr>
              {headers.map((h) => (
                <th
                  key={h}
                  className="px-4 py-3 text-left text-xs font-medium uppercase tracking-wide text-muted-foreground"
                >
                  {h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {rows.length === 0 ? (
              <tr>
                <td colSpan={headers.length} className="px-4 py-8 text-center text-muted-foreground">
                  {t("table.emptyMessage")}
                </td>
              </tr>
            ) : (
              rows.map((row) => (
                <tr key={row.dedupKey}>
                  <td className="px-4 py-3 font-mono tabular-nums text-xs text-muted-foreground">{row.date}</td>
                  <td className="px-4 py-3 text-foreground">{row.description}</td>
                  <td className="px-4 py-3 text-muted-foreground">{row.category ?? "—"}</td>
                  <td className="px-4 py-3 text-muted-foreground">{getTypeLabel(row.type)}</td>
                  <td className="px-4 py-3 font-mono tabular-nums text-foreground">{row.amount}</td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    );
  }

  return (
    <pre className="max-h-[70vh] overflow-auto whitespace-pre-wrap rounded-lg border border-border bg-muted/20 p-3 text-xs text-foreground">
      {textContent ?? ""}
    </pre>
  );
}
