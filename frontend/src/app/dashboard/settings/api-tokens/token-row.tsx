"use client";

import { Trash2 } from "lucide-react";
import { useLocale, useTranslations } from "next-intl";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { formatDate } from "@/lib/utils";
import type { ApiToken } from "@/services/api-token-service";

export interface TokenRowProps {
  token: ApiToken;
  isConfirmingRevoke: boolean;
  onRevokeRequest: () => void;
  onRevokeCancel: () => void;
  onRevokeConfirm: () => void;
  isRevokePending: boolean;
}

export function TokenRow({
  token,
  isConfirmingRevoke,
  onRevokeRequest,
  onRevokeCancel,
  onRevokeConfirm,
  isRevokePending,
}: TokenRowProps) {
  const t = useTranslations("apiTokens.row");
  const tCommon = useTranslations("common");
  const locale = useLocale();
  const isDead = token.revoked || new Date(token.expiresAt) < new Date();

  if (isConfirmingRevoke) {
    return (
      <div className="px-4 py-3">
        <div className="flex items-center justify-between gap-4">
          <p className="text-sm text-muted-foreground">
            {t.rich("revokeConfirm", {
              tokenName: token.name,
              strong: (chunks) => <span className="font-medium text-foreground">{chunks}</span>,
            })}
          </p>
          <div className="flex shrink-0 gap-2">
            <Button variant="destructive" size="sm" onClick={onRevokeConfirm} disabled={isRevokePending}>
              {isRevokePending ? t("revoking") : t("revoke")}
            </Button>
            <Button variant="secondary" size="sm" onClick={onRevokeCancel}>
              {tCommon("cancel")}
            </Button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="px-4 py-3">
      <div className="flex items-center justify-between gap-3">
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <span className="truncate font-medium text-foreground">{token.name}</span>
            <Badge variant={token.scope === "WRITE" ? "expense" : "neutral"}>
              {token.scope === "WRITE" ? t("readWrite") : t("readOnly")}
            </Badge>
            {token.revoked && <Badge variant="neutral">{t("revoked")}</Badge>}
            {!token.revoked && isDead && <Badge variant="neutral">{t("expired")}</Badge>}
          </div>
          <p className="mt-1 truncate font-mono text-xs text-muted-foreground/70">
            {token.tokenPrefix}••••••••
          </p>
          <p className="mt-1 text-xs text-muted-foreground">
            {t("created", { date: formatDate(token.createdAt, locale) })} ·{" "}
            {t("expires", { date: formatDate(token.expiresAt, locale) })} ·{" "}
            {t("lastUsed", { date: token.lastUsedAt ? formatDate(token.lastUsedAt, locale) : t("never") })}
          </p>
        </div>

        {!token.revoked && (
          <button
            onClick={onRevokeRequest}
            className="inline-flex min-h-11 min-w-11 shrink-0 items-center justify-center rounded text-muted-foreground transition-colors hover:bg-destructive/10 hover:text-destructive"
            aria-label={t("revokeAria", { tokenName: token.name })}
          >
            <Trash2 className="h-3.5 w-3.5" />
          </button>
        )}
      </div>
    </div>
  );
}
