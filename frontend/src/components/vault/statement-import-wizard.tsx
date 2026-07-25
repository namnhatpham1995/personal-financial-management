"use client";

import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { vaultService } from "@/services/vault-service";
import { categoryService } from "@/services/category-service";
import { toast } from "sonner";
import { CheckSquare, Square, Loader2, TriangleAlert } from "lucide-react";
import { useLocale, useTranslations } from "next-intl";
import { cn, formatDate } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { useIdempotencyKey } from "@/lib/use-idempotency-key";
import { getIdempotencyErrorCode } from "@/lib/idempotency-error";

interface Props {
  /** Resumes review of this UPLOADED/STAGED statement — parse is safely re-runnable. */
  documentId: string;
  onComplete?: (created: number) => void;
}

export function StatementImportWizard({ documentId, onComplete }: Props) {
  const t = useTranslations("vault.importWizard");
  const tCommon = useTranslations("common");
  const locale = useLocale();
  const qc = useQueryClient();
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [categoryOverrides, setCategoryOverrides] = useState<Record<string, number | null>>({});
  const [createdCount, setCreatedCount] = useState<number | null>(null);

  // Parse is deterministic and side-effect-free, so resuming review for any UPLOADED or
  // previously-STAGED document by id can always (re-)trigger it safely.
  const rowsQuery = useQuery({
    queryKey: ["vault-import-rows", documentId],
    queryFn: () => vaultService.parseImport(documentId),
    retry: false,
  });

  const { data: categories = [] } = useQuery({
    queryKey: ["categories"],
    queryFn: () => categoryService.list(),
  });

  useEffect(() => {
    if (rowsQuery.data) {
      setSelected(new Set(rowsQuery.data.map((r) => r.dedupKey)));
      setCategoryOverrides(Object.fromEntries(rowsQuery.data.map((r) => [r.dedupKey, r.categoryId ?? null])));
    }
  }, [rowsQuery.data]);

  const confirmIdempotency = useIdempotencyKey(null);

  const confirmMut = useMutation({
    mutationFn: () => {
      const selectedRows = Array.from(selected).map((dedupKey) => ({
        dedupKey,
        categoryId: categoryOverrides[dedupKey] ?? null,
      }));
      return vaultService.confirmImport(
        documentId,
        selectedRows,
        confirmIdempotency.resolve({ documentId, selectedRows })
      );
    },
    onSuccess: (n) => {
      confirmIdempotency.clear();
      setCreatedCount(n);
      toast.success(t("imported", { count: n }));
      qc.invalidateQueries({ queryKey: ["vault-by-account"] });
      onComplete?.(n);
    },
    onError: (err) => {
      const idempotencyCode = getIdempotencyErrorCode(err);
      if (idempotencyCode === "idempotency_key_conflict") {
        confirmIdempotency.clear();
        toast.error(t("toast.importFailed"));
      } else if (idempotencyCode === "operation_in_progress") {
        toast.error(tCommon("operationInProgress"));
      } else {
        toast.error(t("toast.importFailed"));
      }
    },
  });

  const rows = rowsQuery.data ?? [];

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

  if (createdCount !== null) {
    return (
      <div className="flex flex-col items-center gap-3 py-8 text-center">
        <div className="rounded-full bg-primary/10 p-3">
          <CheckSquare className="h-6 w-6 text-primary" />
        </div>
        <p className="text-sm font-medium text-foreground">{t("imported", { count: createdCount })}</p>
      </div>
    );
  }

  if (rowsQuery.isPending) {
    return (
      <div className="flex flex-col items-center gap-3 py-8 text-center">
        <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
        <p className="text-sm text-muted-foreground">{t("loadingRows")}</p>
      </div>
    );
  }

  if (rowsQuery.isError) {
    return (
      <div className="flex flex-col items-center gap-3 py-8 text-center">
        <TriangleAlert className="h-6 w-6 text-destructive" />
        <p className="text-sm text-foreground">{t("rowsLoadFailed")}</p>
        <Button size="sm" variant="secondary" onClick={() => rowsQuery.refetch()}>
          {t("retryLoadingRows")}
        </Button>
      </div>
    );
  }

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

      <div className="max-h-96 overflow-y-auto rounded-lg border border-border divide-y divide-border">
        {rows.map((row) => (
          <div
            key={row.dedupKey}
            className={cn(
              "flex flex-wrap items-center gap-3 px-3 py-2 transition-colors",
              selected.has(row.dedupKey) ? "bg-primary/5" : ""
            )}
          >
            <button
              onClick={() => toggle(row.dedupKey)}
              aria-label={t("toggleRow")}
              className="shrink-0"
            >
              {selected.has(row.dedupKey) ? (
                <CheckSquare className="h-4 w-4 text-primary" />
              ) : (
                <Square className="h-4 w-4 text-muted-foreground" />
              )}
            </button>
            <span className="w-24 shrink-0 text-xs text-muted-foreground">
              {formatDate(row.date, locale)}
            </span>
            <span className="min-w-[8rem] flex-1 truncate text-sm text-foreground">
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
            <select
              value={categoryOverrides[row.dedupKey] ?? ""}
              onChange={(e) =>
                setCategoryOverrides((prev) => ({
                  ...prev,
                  [row.dedupKey]: e.target.value ? Number(e.target.value) : null,
                }))
              }
              className="w-36 shrink-0 rounded-md border border-border bg-card px-2 py-1 text-xs text-foreground focus:outline-none focus:ring-2 focus:ring-primary/40"
            >
              <option value="">{t("uncategorized")}</option>
              {categories
                .filter((c) => c.transactionType === row.type)
                .map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name}
                  </option>
                ))}
            </select>
          </div>
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
