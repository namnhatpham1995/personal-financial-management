"use client";

import { Suspense, useState } from "react";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { vaultService, VaultDocument } from "@/services/vault-service";
import { accountService } from "@/services/account-service";
import { StatementImportWizard } from "@/components/vault/statement-import-wizard";
import { ReceiptUploadViewer } from "@/components/vault/receipt-upload-viewer";
import { formatDate } from "@/lib/utils";
import { toast } from "sonner";
import { Receipt, FileText, Download, Trash2, ChevronLeft, ChevronRight } from "lucide-react";
import { cn } from "@/lib/utils";

export default function VaultPage() {
  return (
    <Suspense fallback={<p className="text-muted-foreground">Loading…</p>}>
      <VaultContent />
    </Suspense>
  );
}

function VaultContent() {
  const qc = useQueryClient();
  const [page, setPage] = useState(0);
  const [activeTab, setActiveTab] = useState<"browse" | "import" | "upload">("browse");
  const [selectedAccountId, setSelectedAccountId] = useState<number | null>(null);

  const { data: vaultPage, isLoading } = useQuery({
    queryKey: ["vault", page],
    queryFn: () => vaultService.list(page, 20),
  });

  const { data: accounts } = useQuery({
    queryKey: ["accounts"],
    queryFn: () => accountService.list(),
  });

  const deleteMut = useMutation({
    mutationFn: (id: string) => vaultService.deleteById(id),
    onSuccess: () => {
      toast.success("Document deleted");
      qc.invalidateQueries({ queryKey: ["vault"] });
    },
    onError: () => toast.error("Delete failed"),
  });

  const openDownload = async (doc: VaultDocument) => {
    try {
      const url = await vaultService.getDownloadUrl(doc.id);
      const a = document.createElement("a");
      a.href = url;
      a.download = doc.originalFilename ?? doc.id;
      a.click();
      setTimeout(() => URL.revokeObjectURL(url), 10_000);
    } catch {
      toast.error("Download failed");
    }
  };

  const totalPages = vaultPage?.totalPages ?? 1;

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold tracking-tight text-foreground">Vault</h1>
      </div>

      <div className="flex gap-1 border-b border-border">
        {(["browse", "import", "upload"] as const).map((tab) => (
          <button
            key={tab}
            onClick={() => setActiveTab(tab)}
            className={cn(
              "px-4 py-2 text-sm font-medium capitalize transition-colors",
              activeTab === tab
                ? "border-b-2 border-primary text-primary"
                : "text-muted-foreground hover:text-foreground"
            )}
          >
            {tab === "import" ? "Import Statement" : tab === "upload" ? "Upload Receipt" : "Browse"}
          </button>
        ))}
      </div>

      {activeTab === "import" && (
        <div className="max-w-lg space-y-4">
          <div className="space-y-2">
            <label className="text-sm font-medium text-foreground">Account</label>
            <select
              className="w-full rounded-md border border-border bg-card px-3.5 py-2.5 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-primary/40"
              value={selectedAccountId ?? ""}
              onChange={(e) => setSelectedAccountId(Number(e.target.value) || null)}
            >
              <option value="">Select account…</option>
              {(accounts ?? []).map((a) => (
                <option key={a.id} value={a.id}>
                  {a.name}
                </option>
              ))}
            </select>
          </div>
          {selectedAccountId && (
            <StatementImportWizard
              accountId={selectedAccountId}
              onComplete={() => qc.invalidateQueries({ queryKey: ["vault"] })}
            />
          )}
        </div>
      )}

      {activeTab === "upload" && (
        <div className="max-w-md">
          <ReceiptUploadViewer
            onLinked={() => {
              toast.success("Receipt saved");
              qc.invalidateQueries({ queryKey: ["vault"] });
              setActiveTab("browse");
            }}
          />
        </div>
      )}

      {activeTab === "browse" && (
        <>
          {isLoading && (
            <p className="text-sm text-muted-foreground">Loading…</p>
          )}

          {!isLoading && (!vaultPage?.content?.length) && (
            <div className="flex flex-col items-center gap-3 py-12 text-center">
              <FileText className="h-10 w-10 text-muted-foreground/40" />
              <p className="text-sm text-muted-foreground">No documents yet</p>
              <p className="text-xs text-muted-foreground">
                Upload a receipt or import a bank statement to get started.
              </p>
            </div>
          )}

          {(vaultPage?.content ?? []).length > 0 && (
            <div className="overflow-x-auto rounded-lg border border-border">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-border bg-muted/30">
                    <th className="px-4 py-3 text-left font-medium text-muted-foreground">Type</th>
                    <th className="px-4 py-3 text-left font-medium text-muted-foreground">File</th>
                    <th className="px-4 py-3 text-left font-medium text-muted-foreground">Date</th>
                    <th className="px-4 py-3 text-left font-medium text-muted-foreground">Status</th>
                    <th className="px-4 py-3" />
                  </tr>
                </thead>
                <tbody className="divide-y divide-border">
                  {(vaultPage?.content ?? []).map((doc) => (
                    <tr key={doc.id} className="hover:bg-muted/20 transition-colors">
                      <td className="px-4 py-3">
                        {doc.type === "RECEIPT" ? (
                          <span className="flex items-center gap-1.5 text-amber-600 dark:text-amber-400">
                            <Receipt className="h-4 w-4" /> Receipt
                          </span>
                        ) : (
                          <span className="flex items-center gap-1.5 text-blue-600 dark:text-blue-400">
                            <FileText className="h-4 w-4" /> Statement
                          </span>
                        )}
                      </td>
                      <td className="px-4 py-3 max-w-[200px] truncate text-foreground">
                        {doc.originalFilename ?? doc.id}
                      </td>
                      <td className="px-4 py-3 text-muted-foreground">
                        {formatDate(doc.capturedAt.split("T")[0])}
                      </td>
                      <td className="px-4 py-3">
                        <span className={cn(
                          "inline-flex rounded-full px-2 py-0.5 text-xs font-medium",
                          doc.status === "ACTIVE"
                            ? "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400"
                            : "bg-yellow-500/10 text-yellow-600 dark:text-yellow-400"
                        )}>
                          {doc.status}
                        </span>
                      </td>
                      <td className="px-4 py-3">
                        <div className="flex items-center justify-end gap-2">
                          {doc.hasBinary && (
                            <button
                              onClick={() => openDownload(doc)}
                              className="inline-flex min-h-10 min-w-10 items-center justify-center text-muted-foreground hover:text-foreground transition-colors"
                              title="Download"
                              aria-label={`Download ${doc.originalFilename ?? "document"}`}
                            >
                              <Download className="h-4 w-4" />
                            </button>
                          )}
                          <button
                            onClick={() => {
                              if (confirm("Delete this document?")) {
                                deleteMut.mutate(doc.id);
                              }
                            }}
                            className="inline-flex min-h-10 min-w-10 items-center justify-center text-muted-foreground hover:text-destructive transition-colors"
                            title="Delete"
                            aria-label={`Delete ${doc.originalFilename ?? "document"}`}
                          >
                            <Trash2 className="h-4 w-4" />
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {totalPages > 1 && (
            <div className="flex items-center justify-between">
              <button
                disabled={page === 0}
                onClick={() => setPage((p) => p - 1)}
                className="flex min-h-11 items-center gap-1 text-sm text-muted-foreground hover:text-foreground disabled:opacity-40 transition-colors"
              >
                <ChevronLeft className="h-4 w-4" /> Prev
              </button>
              <span className="text-sm text-muted-foreground">
                {page + 1} / {totalPages}
              </span>
              <button
                disabled={page + 1 >= totalPages}
                onClick={() => setPage((p) => p + 1)}
                className="flex min-h-11 items-center gap-1 text-sm text-muted-foreground hover:text-foreground disabled:opacity-40 transition-colors"
              >
                Next <ChevronRight className="h-4 w-4" />
              </button>
            </div>
          )}
        </>
      )}
    </div>
  );
}
