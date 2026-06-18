"use client";

import { useQuery } from "@tanstack/react-query";
import { activityService, ActivityEvent } from "@/services/activity-service";
import { Card } from "@/components/ui/card";
import { useState } from "react";
import { Activity, ChevronLeft, ChevronRight } from "lucide-react";

function actionLabel(action: string): string {
  return action
    .replace(".", " ")
    .replace(/\b\w/g, (c) => c.toUpperCase());
}

function actionColor(action: string): string {
  if (action.includes("deleted")) return "text-red-400";
  if (action.includes("created")) return "text-emerald-400";
  if (action.includes("login"))   return "text-sky-400";
  return "text-slate-300";
}

function EventRow({ event }: { event: ActivityEvent }) {
  const date = new Date(event.ts);
  return (
    <div className="flex items-start gap-4 py-3 border-b border-slate-800/50 last:border-0">
      <div className="mt-0.5 flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-full bg-slate-800">
        <Activity className="h-4 w-4 text-slate-400" />
      </div>
      <div className="flex-1 min-w-0">
        <p className={`text-sm font-medium ${actionColor(event.action)}`}>
          {actionLabel(event.action)}
        </p>
        <p className="text-xs text-slate-500 mt-0.5">
          {date.toLocaleDateString()} {date.toLocaleTimeString()}
        </p>
      </div>
      <span className="text-xs text-slate-600 hidden sm:block truncate max-w-[120px]">
        {event.correlationId?.slice(0, 8)}
      </span>
    </div>
  );
}

export default function ActivityPage() {
  const [page, setPage] = useState(0);

  const { data, isLoading } = useQuery({
    queryKey: ["activity", page],
    queryFn: () => activityService.list(page, 20),
  });

  return (
    <div className="space-y-6 p-6">
      <div>
        <h1 className="text-2xl font-bold text-slate-100">Recent Activity</h1>
        <p className="text-sm text-slate-500 mt-1">
          A log of changes made to your account — stored in MongoDB.
        </p>
      </div>

      <Card className="bg-slate-900/60 border-slate-800/60 p-4">
        {isLoading && (
          <div className="py-12 text-center text-slate-500 text-sm">Loading…</div>
        )}

        {!isLoading && (!data?.content?.length) && (
          <div className="py-12 text-center text-slate-500 text-sm">
            No activity yet. Make some changes and come back.
          </div>
        )}

        {data?.content?.map((event) => (
          <EventRow key={event.id} event={event} />
        ))}
      </Card>

      {data && data.totalPages > 1 && (
        <div className="flex items-center justify-end gap-2">
          <button
            onClick={() => setPage((p) => Math.max(0, p - 1))}
            disabled={page === 0}
            className="rounded-lg border border-slate-700 p-2 text-slate-400 hover:text-slate-100 disabled:opacity-30"
          >
            <ChevronLeft className="h-4 w-4" />
          </button>
          <span className="text-sm text-slate-500">
            {page + 1} / {data.totalPages}
          </span>
          <button
            onClick={() => setPage((p) => Math.min(data.totalPages - 1, p + 1))}
            disabled={page >= data.totalPages - 1}
            className="rounded-lg border border-slate-700 p-2 text-slate-400 hover:text-slate-100 disabled:opacity-30"
          >
            <ChevronRight className="h-4 w-4" />
          </button>
        </div>
      )}
    </div>
  );
}
