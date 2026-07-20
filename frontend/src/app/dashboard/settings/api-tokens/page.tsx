"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { toast } from "sonner";
import { Plus } from "lucide-react";
import { useTranslations } from "next-intl";
import {
  apiTokenService,
  type CreateApiTokenPayload,
} from "@/services/api-token-service";
import { CreatedTokenBanner } from "./created-token-banner";
import { TokenRow } from "./token-row";
import { Button } from "@/components/ui/button";
import { useIdempotencyKey } from "@/lib/use-idempotency-key";
import { getIdempotencyErrorCode } from "@/lib/idempotency-error";

function createSchema(t: (key: string) => string) {
  return z.object({
    name: z.string().min(1, t("validation.nameRequired")).max(100),
    scope: z.enum(["READ", "WRITE"]),
    expiryDays: z.coerce.number().refine((v) => [30, 90, 365].includes(v), t("validation.invalidExpiry")),
  });
}

type CreateValues = z.infer<ReturnType<typeof createSchema>>;

const inputCls =
  "rounded-md border border-border bg-card px-3.5 py-2.5 text-base text-foreground placeholder:text-muted-foreground/50 focus:outline-none focus:ring-2 focus:ring-primary/40 focus:border-primary/40 transition-colors";

export default function ApiTokensPage() {
  const qc = useQueryClient();
  const t = useTranslations("apiTokens");
  const tCommon = useTranslations("common");
  const [showForm, setShowForm] = useState(false);
  const [confirmRevokeId, setConfirmRevokeId] = useState<number | null>(null);
  const [createdPlaintext, setCreatedPlaintext] = useState<string | null>(null);
  const [ambiguousCreate, setAmbiguousCreate] = useState(false);

  const { data: tokens = [], isLoading } = useQuery({
    queryKey: ["api-tokens"],
    queryFn: apiTokenService.list,
  });

  const invalidate = () => qc.invalidateQueries({ queryKey: ["api-tokens"] });

  const createIdempotency = useIdempotencyKey(null);

  const createMutation = useMutation({
    mutationFn: (data: CreateApiTokenPayload) =>
      apiTokenService.create(data, createIdempotency.resolve(data)),
    onSuccess: (created) => {
      createIdempotency.clear();
      setAmbiguousCreate(false);
      invalidate();
      setShowForm(false);
      setCreatedPlaintext(created.plaintextToken);
    },
    onError: (err) => {
      const idempotencyCode = getIdempotencyErrorCode(err);
      if (idempotencyCode === "idempotency_key_conflict") {
        // Ambiguous delivery: the create may have already succeeded server-side on an
        // earlier attempt with this same key. Never auto-retry (that could mint a second
        // token) — surface guidance and let the user decide, after refreshing the list so
        // they can see whether a token from this attempt is already there.
        setAmbiguousCreate(true);
        invalidate();
      } else if (idempotencyCode === "operation_in_progress") {
        toast.error(tCommon("operationInProgress"));
      } else {
        toast.error(t("toast.createFailed"));
      }
    },
  });

  const startNewAttempt = () => {
    createIdempotency.clear();
    setAmbiguousCreate(false);
  };

  const revokeMutation = useMutation({
    mutationFn: (id: number) => apiTokenService.revoke(id),
    onSuccess: () => {
      invalidate();
      setConfirmRevokeId(null);
      toast.success(t("toast.revoked"));
    },
    onError: () => toast.error(t("toast.revokeFailed")),
  });

  return (
    <div className="space-y-6">
      <div>
        <div className="flex items-center justify-between">
          <h1 className="text-2xl font-bold tracking-tight text-foreground">{t("title")}</h1>
          <Button onClick={() => setShowForm(!showForm)}>
            <Plus className="h-4 w-4" /> {t("newToken")}
          </Button>
        </div>
        <p className="mt-1 text-sm text-muted-foreground">
          {t("description")}
        </p>
      </div>

      {createdPlaintext && (
        <CreatedTokenBanner
          plaintextToken={createdPlaintext}
          onDismiss={() => setCreatedPlaintext(null)}
        />
      )}

      {ambiguousCreate && (
        <div className="rounded-lg border border-amber-500/30 bg-amber-500/5 p-5">
          <p className="font-medium text-foreground">{t("recovery.title")}</p>
          <p className="mt-1 text-sm text-muted-foreground">{t("recovery.body")}</p>
          <div className="mt-3">
            <Button type="button" size="sm" variant="secondary" onClick={startNewAttempt}>
              {t("recovery.tryAgain")}
            </Button>
          </div>
        </div>
      )}

      {showForm && (
        <CreateTokenForm
          onSubmit={(values) => createMutation.mutate(values)}
          onCancel={() => setShowForm(false)}
          isPending={createMutation.isPending}
        />
      )}

      {isLoading ? (
        <p className="text-muted-foreground">{tCommon("loading")}</p>
      ) : tokens.length === 0 ? (
        <p className="text-sm text-muted-foreground">{t("emptyState")}</p>
      ) : (
        <div className="divide-y divide-border rounded-lg border border-border bg-card">
          {tokens.map((token) => (
            <TokenRow
              key={token.id}
              token={token}
              isConfirmingRevoke={confirmRevokeId === token.id}
              onRevokeRequest={() => setConfirmRevokeId(token.id)}
              onRevokeCancel={() => setConfirmRevokeId(null)}
              onRevokeConfirm={() => revokeMutation.mutate(token.id)}
              isRevokePending={revokeMutation.isPending && confirmRevokeId === token.id}
            />
          ))}
        </div>
      )}
    </div>
  );
}

function CreateTokenForm({
  onSubmit,
  onCancel,
  isPending,
}: {
  onSubmit: (v: CreateValues) => void;
  onCancel: () => void;
  isPending: boolean;
}) {
  const t = useTranslations("apiTokens");
  const tCommon = useTranslations("common");
  const schema = useMemo(() => createSchema(t), [t]);
  const { register, handleSubmit, formState: { errors } } = useForm<CreateValues>({
    resolver: zodResolver(schema),
    defaultValues: { scope: "READ", expiryDays: 90 },
  });

  return (
    <div className="rounded-lg border border-border bg-card p-5">
      <h2 className="mb-4 font-semibold tracking-tight text-foreground">{t("form.title")}</h2>
      <form onSubmit={handleSubmit(onSubmit)} className="flex flex-wrap items-end gap-4">
        <div className="min-w-40 flex-1">
          <label className="mb-1.5 block text-xs font-medium uppercase tracking-wide text-muted-foreground">
            {t("form.fields.name")}
          </label>
          <input {...register("name")} placeholder={t("form.namePlaceholder")} className={`w-full ${inputCls}`} />
          {errors.name && <p className="mt-1 text-xs text-destructive">{errors.name.message}</p>}
        </div>
        <div>
          <label className="mb-1.5 block text-xs font-medium uppercase tracking-wide text-muted-foreground">
            {t("form.fields.scope")}
          </label>
          <select {...register("scope")} className={inputCls}>
            <option value="READ">{t("form.scope.readOnly")}</option>
            <option value="WRITE">{t("form.scope.readWrite")}</option>
          </select>
        </div>
        <div>
          <label className="mb-1.5 block text-xs font-medium uppercase tracking-wide text-muted-foreground">
            {t("form.fields.expiresIn")}
          </label>
          <select {...register("expiryDays")} className={inputCls}>
            <option value={30}>{t("form.expiry.days30")}</option>
            <option value={90}>{t("form.expiry.days90")}</option>
            <option value={365}>{t("form.expiry.days365")}</option>
          </select>
        </div>
        <div className="flex gap-2">
          <Button type="submit" disabled={isPending}>
            {isPending ? t("form.creating") : t("form.create")}
          </Button>
          <Button type="button" variant="secondary" onClick={onCancel}>
            {tCommon("cancel")}
          </Button>
        </div>
      </form>
    </div>
  );
}
