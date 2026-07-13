"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Activity, AlertCircle, ChevronLeft, ChevronRight } from "lucide-react";
import { useTranslations } from "next-intl";
import { activityService, ActivityEvent } from "@/services/activity-service";
import { Card } from "@/components/ui/card";

function actionLabel(action: string): string {
  return action.replace(".", " ").replace(/\b\w/g, (c) => c.toUpperCase());
}

function actionColor(action: string): string {
  if (action.includes("deleted")) return "text-destructive";
  if (action.includes("created")) return "text-success";
  if (action.includes("login")) return "text-sky-600 dark:text-sky-400";
  return "text-foreground";
}

function EventRow({ event }: { event: ActivityEvent }) {
  const date = new Date(event.ts);

  return (
    <div className="flex items-start gap-4 border-b border-border py-3 last:border-0">
      <div className="mt-0.5 flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-md bg-secondary">
        <Activity className="h-4 w-4 text-muted-foreground" />
      </div>
      <div className="min-w-0 flex-1">
        <p className={`text-sm font-medium ${actionColor(event.action)}`}>
          {actionLabel(event.action)}
        </p>
        <p className="mt-0.5 font-mono text-xs tabular-nums text-muted-foreground">
          {date.toLocaleDateString()} {date.toLocaleTimeString()}
        </p>
      </div>
    </div>
  );
}

export default function ActivityPage() {
  const t = useTranslations("activity");
  const [page, setPage] = useState(0);

  const { data, isLoading, isError, refetch } = useQuery({
    queryKey: ["activity", page],
    queryFn: () => activityService.list(page, 20),
  });

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold tracking-tight text-foreground">{t("title")}</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          {t("description")}
        </p>
      </div>

      <Card className="p-4">
        {isLoading && (
          <div className="space-y-3 py-2">
            {Array.from({ length: 5 }).map((_, index) => (
              <div key={index} className="flex items-center gap-4 py-2">
                <div className="h-8 w-8 rounded-lg bg-secondary" />
                <div className="flex-1 space-y-2">
                  <div className="h-3 w-36 rounded bg-secondary" />
                  <div className="h-3 w-24 rounded bg-secondary/70" />
                </div>
              </div>
            ))}
          </div>
        )}

        {!isLoading && isError && (
          <div className="py-12 text-center text-sm">
            <AlertCircle className="mx-auto h-6 w-6 text-destructive" />
            <p className="mt-2 font-medium text-foreground">{t("errorTitle")}</p>
            <p className="mt-1 text-muted-foreground">
              {t("errorBody")}
            </p>
            <button
              onClick={() => refetch()}
              className="mt-4 inline-flex min-h-11 items-center justify-center rounded-sm border border-border px-4 text-sm font-medium text-foreground transition-colors hover:bg-secondary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/40"
            >
              {t("retry")}
            </button>
          </div>
        )}

        {!isLoading && !isError && !data?.content?.length && (
          <div className="py-12 text-center text-sm">
            <p className="font-medium text-foreground">{t("emptyTitle")}</p>
            <p className="mt-1 text-muted-foreground">
              {t("emptyBody")}
            </p>
          </div>
        )}

        {!isError &&
          data?.content?.map((event) => (
            <EventRow key={event.id} event={event} />
          ))}
      </Card>

      {data && data.totalPages > 1 && (
        <div className="flex items-center justify-end gap-2">
          <button
            onClick={() => setPage((p) => Math.max(0, p - 1))}
            disabled={page === 0}
            className="inline-flex min-h-11 min-w-11 items-center justify-center rounded-sm border border-border text-muted-foreground transition-colors hover:text-foreground disabled:opacity-30 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/40"
            aria-label={t("prevAria")}
          >
            <ChevronLeft className="h-4 w-4" />
          </button>
          <span className="font-mono text-sm tabular-nums text-muted-foreground">
            {t("pageOf", { current: page + 1, total: data.totalPages })}
          </span>
          <button
            onClick={() => setPage((p) => Math.min(data.totalPages - 1, p + 1))}
            disabled={page >= data.totalPages - 1}
            className="inline-flex min-h-11 min-w-11 items-center justify-center rounded-sm border border-border text-muted-foreground transition-colors hover:text-foreground disabled:opacity-30 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/40"
            aria-label={t("nextAria")}
          >
            <ChevronRight className="h-4 w-4" />
          </button>
        </div>
      )}
    </div>
  );
}
