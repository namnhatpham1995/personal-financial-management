"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { useLocale, useTranslations } from "next-intl";
import { toast } from "sonner";
import { ArrowLeft, Loader2, X } from "lucide-react";
import {
  agentRunService,
  isAgentFeatureUnavailable,
  type AgentProposal,
} from "@/services/agent-run-service";
import { categoryService } from "@/services/category-service";
import { accountService } from "@/services/account-service";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { cn, formatDate } from "@/lib/utils";

export default function ReceiptRunDetailPage({ params }: { params: { id: string } }) {
  const runId = Number(params.id);
  const t = useTranslations("receipts");
  const tCommon = useTranslations("common");
  const locale = useLocale();
  const qc = useQueryClient();

  const { data: run, isLoading, error } = useQuery({
    queryKey: ["agent-runs", runId],
    queryFn: () => agentRunService.getById(runId),
    retry: false,
  });

  const { data: categories = [] } = useQuery({
    queryKey: ["categories"],
    queryFn: () => categoryService.list(),
    enabled: run?.status === "AWAITING_REVIEW",
  });
  const { data: accounts = [] } = useQuery({
    queryKey: ["accounts"],
    queryFn: () => accountService.list(),
    enabled: run?.status === "AWAITING_REVIEW",
  });

  const [proposals, setProposals] = useState<AgentProposal[]>([]);

  useEffect(() => {
    if (run?.proposals) setProposals(run.proposals);
  }, [run?.proposals]);

  const decisionMut = useMutation({
    mutationFn: (approve: boolean) => agentRunService.decide(runId, { approve, proposals }),
    onSuccess: (updated) => {
      qc.setQueryData(["agent-runs", runId], updated);
      qc.invalidateQueries({ queryKey: ["agent-runs"] });
      toast.success(updated.status === "COMMITTED" ? t("toast.approved") : t("toast.rejected"));
    },
    onError: () => toast.error(t("toast.decisionFailed")),
  });

  const unavailable = isAgentFeatureUnavailable(error);
  const inFlight = decisionMut.isPending;

  const updateProposal = (index: number, patch: Partial<AgentProposal>) => {
    setProposals((prev) => prev.map((p, i) => (i === index ? { ...p, ...patch } : p)));
  };

  const includedProposals = proposals.filter((p) => !p.excluded);
  const canApprove =
    includedProposals.length > 0 &&
    includedProposals.every((p) => p.categoryId != null && p.accountId != null);

  return (
    <div className="space-y-6">
      <Link href="/dashboard/receipts" className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground transition-colors">
        <ArrowLeft className="h-4 w-4" /> {t("backToList")}
      </Link>

      {isLoading && <p className="text-sm text-muted-foreground">{tCommon("loading")}</p>}

      {!isLoading && unavailable && (
        <div className="py-12 text-center">
          <p className="text-sm font-medium text-foreground">{t("unavailable.title")}</p>
          <p className="text-xs text-muted-foreground">{t("unavailable.body")}</p>
        </div>
      )}

      {!isLoading && !unavailable && (error || !run) && (
        <p className="text-sm text-muted-foreground">{t("notFound")}</p>
      )}

      {!isLoading && run && (
        <div className="space-y-6">
          <div className="flex items-center justify-between">
            <h1 className="text-2xl font-bold tracking-tight text-foreground">{t("detail.title")}</h1>
            <StatusBadge status={run.status} label={t(`status.${run.status}`)} />
          </div>

          {run.extraction && (
            <div className="flex flex-wrap gap-6 rounded-lg border border-border bg-muted/20 p-4 text-sm">
              <div>
                <p className="text-xs text-muted-foreground">{t("detail.extractedMerchant")}</p>
                <p className="font-medium text-foreground">{run.extraction.merchant}</p>
              </div>
              <div>
                <p className="text-xs text-muted-foreground">{t("detail.extractedTotal")}</p>
                <p className="font-medium text-foreground">
                  {run.extraction.total} {run.extraction.currency}
                </p>
              </div>
            </div>
          )}

          {run.status === "FAILED" && (
            <div className="rounded-lg border border-destructive/20 bg-destructive/5 p-4 text-sm">
              <p className="font-medium text-destructive">{t("detail.failureReason")}</p>
              <p className="text-muted-foreground">{run.failureReason}</p>
              <p className="mt-2 text-xs text-muted-foreground">{t("detail.retryHint")}</p>
              <Badge variant={run.retryable ? "income" : "neutral"} className="mt-2">
                {run.retryable ? t("detail.retryableBadge") : t("detail.notRetryableBadge")}
              </Badge>
            </div>
          )}

          {(run.status === "AWAITING_REVIEW" || run.status === "COMMITTED" || run.status === "REJECTED") &&
            proposals.length > 0 && (
              <div className="space-y-3">
                <h2 className="text-sm font-semibold text-foreground">{t("detail.proposalsTitle")}</h2>
                <div className="space-y-3">
                  {proposals.map((proposal, index) => (
                    <ProposalCard
                      key={index}
                      proposal={proposal}
                      editable={run.status === "AWAITING_REVIEW"}
                      categories={categories}
                      accounts={accounts}
                      locale={locale}
                      t={t}
                      onChange={(patch) => updateProposal(index, patch)}
                    />
                  ))}
                </div>
              </div>
            )}

          {run.status === "COMMITTED" && run.createdTransactionIds && run.createdTransactionIds.length > 0 && (
            <div className="rounded-lg border border-emerald-500/20 bg-emerald-500/5 p-4 text-sm">
              <p className="font-medium text-emerald-600 dark:text-emerald-400">
                {t("detail.committedTransactions", { count: run.createdTransactionIds.length })}
              </p>
              <div className="mt-2 flex flex-wrap gap-2">
                {run.createdTransactionIds.map((id) => (
                  <Badge key={id} variant="income">
                    #{id}
                  </Badge>
                ))}
              </div>
            </div>
          )}

          {run.status === "AWAITING_REVIEW" && (
            <div className="flex items-center justify-end gap-3">
              <Button
                variant="secondary"
                disabled={inFlight}
                onClick={() => decisionMut.mutate(false)}
              >
                {inFlight && decisionMut.variables === false && <Loader2 className="h-3.5 w-3.5 animate-spin" />}
                {inFlight && decisionMut.variables === false ? t("detail.rejecting") : t("detail.reject")}
              </Button>
              <Button
                disabled={inFlight || !canApprove}
                onClick={() => decisionMut.mutate(true)}
              >
                {inFlight && decisionMut.variables === true && <Loader2 className="h-3.5 w-3.5 animate-spin" />}
                {inFlight && decisionMut.variables === true ? t("detail.approving") : t("detail.approve")}
              </Button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function StatusBadge({ status, label }: { status: string; label: string }) {
  const cls: Record<string, string> = {
    AWAITING_REVIEW: "bg-primary/10 text-primary",
    EXTRACTING: "bg-yellow-500/10 text-yellow-600 dark:text-yellow-400",
    COMMITTED: "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400",
    REJECTED: "bg-secondary text-muted-foreground",
    FAILED: "bg-destructive/10 text-destructive",
  };
  return (
    <span className={cn("inline-flex rounded-full px-3 py-1 text-xs font-medium", cls[status])}>
      {label}
    </span>
  );
}

function ProposalCard({
  proposal,
  editable,
  categories,
  accounts,
  locale,
  t,
  onChange,
}: {
  proposal: AgentProposal;
  editable: boolean;
  categories: Array<{ id: number; name: string; transactionType: string }>;
  accounts: Array<{ id: number; name: string; currency: string }>;
  locale: string;
  t: ReturnType<typeof useTranslations>;
  onChange: (patch: Partial<AgentProposal>) => void;
}) {
  const expenseCategories = categories.filter((c) => c.transactionType === "EXPENSE");

  return (
    <div
      className={cn(
        "rounded-lg border border-border p-4 space-y-3",
        proposal.excluded && "opacity-50"
      )}
    >
      {proposal.flags.length > 0 && (
        <div className="flex flex-wrap gap-1.5">
          {proposal.flags.map((flag) => (
            <Badge key={flag} variant="expense">
              {flagLabel(t, flag)}
            </Badge>
          ))}
        </div>
      )}

      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <Field label={t("detail.merchant")}>
          <p className="text-sm font-medium text-foreground">{proposal.merchant}</p>
        </Field>
        <Field label={t("detail.date")}>
          {editable ? (
            <input
              type="date"
              value={proposal.date}
              onChange={(e) => onChange({ date: e.target.value })}
              className="w-full rounded-md border border-border bg-card px-2 py-1 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-primary/40"
            />
          ) : (
            <p className="text-sm text-foreground">{formatDate(proposal.date, locale)}</p>
          )}
        </Field>
        <Field label={t("detail.amount")}>
          {editable ? (
            <input
              type="number"
              step="0.01"
              value={proposal.amount}
              onChange={(e) => onChange({ amount: e.target.value })}
              className="w-full rounded-md border border-border bg-card px-2 py-1 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-primary/40"
            />
          ) : (
            <p className="text-sm font-medium tabular-nums text-foreground">
              {proposal.amount} {proposal.currency}
            </p>
          )}
        </Field>
        <Field label={t("detail.category")}>
          {editable ? (
            <select
              value={proposal.categoryId ?? ""}
              onChange={(e) => onChange({ categoryId: e.target.value ? Number(e.target.value) : null })}
              className="w-full rounded-md border border-border bg-card px-2 py-1 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-primary/40"
            >
              <option value="">{t("detail.selectCategory")}</option>
              {expenseCategories.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </select>
          ) : (
            <p className="text-sm text-foreground">
              {categories.find((c) => c.id === proposal.categoryId)?.name ?? "—"}
            </p>
          )}
        </Field>
      </div>

      {editable && (
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          <Field label={t("detail.account")}>
            <select
              value={proposal.accountId ?? ""}
              onChange={(e) => onChange({ accountId: e.target.value ? Number(e.target.value) : null })}
              className="w-full rounded-md border border-border bg-card px-2 py-1 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-primary/40"
            >
              <option value="">{t("detail.selectAccount")}</option>
              {accounts.map((a) => (
                <option key={a.id} value={a.id}>
                  {a.name}
                </option>
              ))}
            </select>
          </Field>
        </div>
      )}

      {editable && (
        <button
          type="button"
          onClick={() => onChange({ excluded: !proposal.excluded })}
          className="flex items-center gap-1.5 text-xs text-muted-foreground hover:text-foreground transition-colors"
        >
          <X className="h-3 w-3" />
          {proposal.excluded ? t("detail.excluded") : t("detail.exclude")}
        </button>
      )}
    </div>
  );
}

const KNOWN_FLAGS = ["low-confidence", "totals-mismatch", "future-date", "unrecognized-currency"];

function flagLabel(t: ReturnType<typeof useTranslations>, flag: string): string {
  return KNOWN_FLAGS.includes(flag) ? t(`detail.flags.${flag}`) : flag;
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="space-y-1">
      <p className="text-xs text-muted-foreground">{label}</p>
      {children}
    </div>
  );
}
