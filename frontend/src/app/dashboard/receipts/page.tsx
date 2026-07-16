"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { useLocale, useTranslations } from "next-intl";
import { Inbox, AlertCircle } from "lucide-react";
import { agentRunService, type AgentRunStatus, isAgentFeatureUnavailable } from "@/services/agent-run-service";
import { formatDate } from "@/lib/utils";
import { cn } from "@/lib/utils";

const statusOrder: Record<AgentRunStatus, number> = {
  AWAITING_REVIEW: 0,
  EXTRACTING: 1,
  FAILED: 2,
  COMMITTED: 3,
  REJECTED: 4,
};

const statusBadgeClass: Record<AgentRunStatus, string> = {
  AWAITING_REVIEW: "bg-primary/10 text-primary",
  EXTRACTING: "bg-yellow-500/10 text-yellow-600 dark:text-yellow-400",
  COMMITTED: "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400",
  REJECTED: "bg-secondary text-muted-foreground",
  FAILED: "bg-destructive/10 text-destructive",
};

export default function ReceiptsPage() {
  const t = useTranslations("receipts");
  const tCommon = useTranslations("common");
  const locale = useLocale();

  const { data: runs, isLoading, error } = useQuery({
    queryKey: ["agent-runs"],
    queryFn: () => agentRunService.list(),
    retry: false,
  });

  const unavailable = isAgentFeatureUnavailable(error);

  const sortedRuns = [...(runs ?? [])].sort((a, b) => {
    const orderDelta = statusOrder[a.status] - statusOrder[b.status];
    if (orderDelta !== 0) return orderDelta;
    return b.createdAt.localeCompare(a.createdAt);
  });

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold tracking-tight text-foreground">{t("title")}</h1>
      </div>

      {isLoading && <p className="text-sm text-muted-foreground">{tCommon("loading")}</p>}

      {!isLoading && unavailable && (
        <div className="flex flex-col items-center gap-3 py-12 text-center">
          <AlertCircle className="h-10 w-10 text-muted-foreground/40" />
          <p className="text-sm font-medium text-foreground">{t("unavailable.title")}</p>
          <p className="text-xs text-muted-foreground">{t("unavailable.body")}</p>
        </div>
      )}

      {!isLoading && !unavailable && error && (
        <div className="flex flex-col items-center gap-3 py-12 text-center">
          <AlertCircle className="h-10 w-10 text-muted-foreground/40" />
          <p className="text-sm text-muted-foreground">{t("loadError")}</p>
        </div>
      )}

      {!isLoading && !error && sortedRuns.length === 0 && (
        <div className="flex flex-col items-center gap-3 py-12 text-center">
          <Inbox className="h-10 w-10 text-muted-foreground/40" />
          <p className="text-sm text-muted-foreground">{t("emptyState.title")}</p>
          <p className="text-xs text-muted-foreground">{t("emptyState.body")}</p>
        </div>
      )}

      {!isLoading && !error && sortedRuns.length > 0 && (
        <div className="overflow-x-auto rounded-lg border border-border">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border bg-muted/30">
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("table.receipt")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("table.status")}</th>
                <th className="px-4 py-3 text-left font-medium text-muted-foreground">{t("table.created")}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {sortedRuns.map((run) => (
                <tr key={run.id} className="hover:bg-muted/20 transition-colors">
                  <td className="px-4 py-3">
                    <Link href={`/dashboard/receipts/${run.id}`} className="text-primary hover:underline">
                      {t("table.receipt")} #{run.id}
                    </Link>
                  </td>
                  <td className="px-4 py-3">
                    <span
                      className={cn(
                        "inline-flex rounded-full px-2 py-0.5 text-xs font-medium",
                        statusBadgeClass[run.status]
                      )}
                    >
                      {t(`status.${run.status}`)}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-muted-foreground">
                    {formatDate(run.createdAt.split("T")[0], locale)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
