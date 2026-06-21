"use client";

import { useRef, useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { vaultService, StagedRow } from "@/services/vault-service";
import { toast } from "sonner";
import { Upload, CheckSquare, Square, Loader2 } from "lucide-react";
import { cn, formatDate } from "@/lib/utils";

interface Props {
  accountId: number;
  onComplete?: (created: number) => void;
}

type Step = "upload" | "review" | "done";

export function StatementImportWizard({ accountId, onComplete }: Props) {
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
    onError: () => toast.error("Failed to parse file"),
  });

  const confirmMut = useMutation({
    mutationFn: () =>
      vaultService.confirmImport(documentId!, Array.from(selected)),
    onSuccess: (n) => {
      setCreatedCount(n);
      setStep("done");
      toast.success(`${n} transaction${n !== 1 ? "s" : ""} imported`);
      onComplete?.(n);
    },
    onError: () => toast.error("Import failed"),
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
        <div className="rounded-full bg-emerald-500/10 p-3">
          <CheckSquare className="h-6 w-6 text-emerald-500" />
        </div>
        <p className="text-sm font-medium text-foreground">
          {createdCount} transaction{createdCount !== 1 ? "s" : ""} imported
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
          Import another file
        </button>
      </div>
    );
  }

  if (step === "review") {
    return (
      <div className="space-y-4">
        <div className="flex items-center justify-between">
          <p className="text-sm font-medium text-foreground">
            {rows.length} rows found — select rows to import
          </p>
          <button
            onClick={toggleAll}
            className="text-xs text-muted-foreground hover:text-foreground transition-colors"
          >
            {selected.size === rows.length ? "Deselect all" : "Select all"}
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
                selected.has(row.dedupKey) ? "bg-emerald-500/5" : ""
              )}
            >
              {selected.has(row.dedupKey) ? (
                <CheckSquare className="h-4 w-4 shrink-0 text-emerald-500" />
              ) : (
                <Square className="h-4 w-4 shrink-0 text-muted-foreground" />
              )}
              <span className="w-24 shrink-0 text-xs text-muted-foreground">
                {formatDate(row.date)}
              </span>
              <span className="flex-1 truncate text-sm text-foreground">
                {row.description}
              </span>
              <span
                className={cn(
                  "shrink-0 text-sm font-medium tabular-nums",
                  row.type === "INCOME"
                    ? "text-emerald-600 dark:text-emerald-400"
                    : "text-red-600 dark:text-red-400"
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
            {selected.size} of {rows.length} selected
          </p>
          <button
            disabled={selected.size === 0 || confirmMut.isPending}
            onClick={() => confirmMut.mutate()}
            className={cn(
              "flex items-center gap-2 rounded-lg bg-emerald-600 px-4 py-2 text-sm font-medium text-white transition-colors",
              "hover:bg-emerald-700 disabled:opacity-50 disabled:cursor-not-allowed"
            )}
          >
            {confirmMut.isPending && <Loader2 className="h-3 w-3 animate-spin" />}
            Import {selected.size} rows
          </button>
        </div>
      </div>
    );
  }

  return (
    <div
      className={cn(
        "flex flex-col items-center justify-center gap-3 rounded-lg border-2 border-dashed border-border p-8",
        "cursor-pointer hover:border-emerald-500/50 hover:bg-emerald-500/5 transition-colors",
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
          {uploadMut.isPending ? "Parsing file…" : "Upload bank statement"}
        </p>
        <p className="text-xs text-muted-foreground mt-1">CSV or OFX/QFX files</p>
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
