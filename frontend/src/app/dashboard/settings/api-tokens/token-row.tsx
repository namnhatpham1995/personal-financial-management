"use client";

import { Trash2 } from "lucide-react";
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
  const isDead = token.revoked || new Date(token.expiresAt) < new Date();

  if (isConfirmingRevoke) {
    return (
      <div className="px-4 py-3">
        <div className="flex items-center justify-between gap-4">
          <p className="text-sm text-muted-foreground">
            Revoke <span className="font-medium text-foreground">{token.name}</span>? Any client using
            this token will immediately lose access.
          </p>
          <div className="flex shrink-0 gap-2">
            <Button variant="destructive" size="sm" onClick={onRevokeConfirm} disabled={isRevokePending}>
              {isRevokePending ? "Revoking..." : "Revoke"}
            </Button>
            <Button variant="secondary" size="sm" onClick={onRevokeCancel}>
              Cancel
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
              {token.scope === "WRITE" ? "Read + Write" : "Read only"}
            </Badge>
            {token.revoked && <Badge variant="neutral">Revoked</Badge>}
            {!token.revoked && isDead && <Badge variant="neutral">Expired</Badge>}
          </div>
          <p className="mt-1 truncate font-mono text-xs text-muted-foreground/70">
            {token.tokenPrefix}••••••••
          </p>
          <p className="mt-1 text-xs text-muted-foreground">
            Created {formatDate(token.createdAt)} · Expires {formatDate(token.expiresAt)} · Last used{" "}
            {token.lastUsedAt ? formatDate(token.lastUsedAt) : "never"}
          </p>
        </div>

        {!token.revoked && (
          <button
            onClick={onRevokeRequest}
            className="inline-flex min-h-11 min-w-11 shrink-0 items-center justify-center rounded text-muted-foreground transition-colors hover:bg-destructive/10 hover:text-destructive"
            aria-label={`Revoke ${token.name}`}
          >
            <Trash2 className="h-3.5 w-3.5" />
          </button>
        )}
      </div>
    </div>
  );
}
