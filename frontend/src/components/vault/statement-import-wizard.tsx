"use client";

import { useRef, useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { vaultService, StagedRow } from "@/services/vault-service";
import { toast } from "sonner";
import { Upload, CheckSquare, Square, Loader2 } from "lucide-react";
import { useLocale, useTranslations } from "next-intl";
import { cn, formatDate } from "@/lib/utils";
import { Button } from "@/components/ui/button";

interface Props {
  accountId: number;
  onComplete?: (created: number) => void;
}

type Step = "upload" | "review" | "done";

export function StatementImportWizard({ accountId, onComplete }: Props) {
  const t = useTranslations("vault.importWizard");
  const locale = useLocale();
  const fileRef = useRef<HTMLInputElement>(null);
  const [step, setStep] = useState<Step>("upload");
  const [documentId, setDocumentId] = useState<string | null>(null);
  const [rows, setRows] = useState<StagedRow[]>([]);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [createdCount, setCreatedCount] = useState(0);

  const uploadMut = useMutation({
    mutationFn: (file: File) => vaultService.importUpload(accountId, file),
    onSuccess: async (docId) => {
      setDocumentId(docId);
      const staged = await vaultService.getImportRows(docId);
      setRows(staged);
      setSelected(new Set(staged.map((r) => r.dedupKey)));
      setStep("review");
    },
    onError: () => toast.error(t("toast.parseFailed")),
  });

  const confirmMut = useMutation({
    mutationFn: () =>
      vaultService.confirmImport(documentId!, Array.from(selected)),
    onSuccess: (n) => {
      setCreatedCount(n);
      setStep("done");
      toast.success(t("imported", { count: n }));
      onComplete?.(n);
    },
    onError: () => toast.error(t("toast.importFailed")),
  });

  const toggleAll = () => {
    if (selected.size === rows.length) {
      setSelected(new Set());
    } else {
      setSelected(new Set(rows.map((r) => r.dedupKey)));
    }
  };

  const toggle = (key: string) => {
    setSelected((prev) => {
      const next = new Set(prev);
      next.has(key) ? next.delete(key) : next.add(key);
      return next;
    });
  };

  if (step === "done") {
    return (
      <div className="flex flex-col items-center gap-3 py-8 text-center">
        <div className="rounded-full bg-primary/10 p-3">
          <CheckSquare className="h-6 w-6 text-primary" />
        </div>
        <p className="text-sm font-medium text-foreground">
          {t("imported", { count: createdCount })}
        </p>
        <button
          onClick={() => {
            setStep("upload");
            setRows([]);
            setSelected(new Set());
            setDocumentId(null);
          }}
          className="text-xs text-muted-foreground hover:text-foreground underline transition-colors"
        >
          {t("importAnother")}
        </button>
      </div>
    );
  }

  if (step === "review") {
    return (
      <div className="space-y-4">
        <div className="flex items-center justify-between">
          <p className="text-sm font-medium text-foreground">
            {t("rowsFound", { count: rows.length })}
          </p>
          <button
            onClick={toggleAll}
            className="text-xs text-muted-foreground hover:text-foreground transition-colors"
          >
            {selected.size === rows.length ? t("deselectAll") : t("selectAll")}
          </button>
        </div>

        <div className="max-h-72 overflow-y-auto rounded-lg border border-border divide-y divide-border">
          {rows.map((row) => (
            <button
              key={row.dedupKey}
              onClick={() => toggle(row.dedupKey)}
              className={cn(
                "flex w-full items-center gap-3 px-3 py-2 text-left transition-colors",
                "hover:bg-muted/50",
                selected.has(row.dedupKey) ? "bg-primary/5" : ""
              )}
            >
              {selected.has(row.dedupKey) ? (
                <CheckSquare className="h-4 w-4 shrink-0 text-primary" />
              ) : (
                <Square className="h-4 w-4 shrink-0 text-muted-foreground" />
              )}
              <span className="w-24 shrink-0 text-xs text-muted-foreground">
                {formatDate(row.date, locale)}
              </span>
              <span className="flex-1 truncate text-sm text-foreground">
                {row.description}
              </span>
              <span
                className={cn(
                  "shrink-0 text-sm font-medium tabular-nums",
                  row.type === "INCOME" ? "text-income" : "text-expense"
                )}
              >
                {row.type === "EXPENSE" ? "-" : "+"}
                {parseFloat(row.amount).toLocaleString()}
              </span>
            </button>
          ))}
        </div>

        <div className="flex items-center justify-between">
          <p className="text-xs text-muted-foreground">
            {t("selectedOfTotal", { selected: selected.size, total: rows.length })}
          </p>
          <Button
            disabled={selected.size === 0 || confirmMut.isPending}
            onClick={() => confirmMut.mutate()}
          >
            {confirmMut.isPending && <Loader2 className="h-3 w-3 animate-spin" />}
            {t("importRows", { count: selected.size })}
          </Button>
        </div>
      </div>
    );
  }

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
          {uploadMut.isPending ? t("parsingFile") : t("uploadPrompt")}
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
