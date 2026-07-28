import type { AgentRunStatus } from "@/services/agent-run-service";
import type { VaultDocument, VaultDocumentStatus, VaultStage } from "@/services/vault-service";

/**
 * Presentation stage for one row in the Vault workspace. Mirrors the backend's
 * `VaultStageMapper` for the seven stages that can be filtered/counted server-side, plus one
 * frontend-only stage: a receipt renders as STORED (not NOT_PROCESSED) when the ingestion
 * agent service is unreachable, since offering an action that cannot be taken would mislead.
 */
export type VaultDisplayStage = VaultStage | "STORED";

const STATEMENT_STAGES: Record<VaultDocumentStatus, VaultStage> = {
  UPLOADED: "READY_TO_IMPORT",
  STAGED: "NEEDS_REVIEW",
  CONFIRMING: "NEEDS_REVIEW",
  ACTIVE: "IMPORTED",
};

const RECEIPT_STAGES: Record<AgentRunStatus, VaultStage> = {
  EXTRACTING: "PROCESSING",
  AWAITING_REVIEW: "NEEDS_REVIEW",
  COMMITTED: "IMPORTED",
  FAILED: "FAILED",
  REJECTED: "DISMISSED",
  INVALIDATED: "DISMISSED",
};

export function deriveStatementStage(status: VaultDocumentStatus): VaultStage {
  return STATEMENT_STAGES[status];
}

/** `ingestionStatus` is null/undefined when the receipt has never been ingested. */
export function deriveReceiptStage(
  ingestionStatus: AgentRunStatus | null | undefined,
  agentFeatureUnavailable: boolean
): VaultDisplayStage {
  if (ingestionStatus == null) {
    return agentFeatureUnavailable ? "STORED" : "NOT_PROCESSED";
  }
  return RECEIPT_STAGES[ingestionStatus];
}

export function deriveDisplayStage(doc: VaultDocument, agentFeatureUnavailable: boolean): VaultDisplayStage {
  if (doc.type === "STATEMENT") {
    return deriveStatementStage(doc.status);
  }
  return deriveReceiptStage(doc.ingestionStatus, agentFeatureUnavailable);
}

/** Stages that represent work the user still needs to act on — the Vault's default filter. */
export const NEEDS_ATTENTION_STAGES: readonly VaultStage[] = ["READY_TO_IMPORT", "NEEDS_REVIEW", "FAILED"];

/**
 * Badge styling keyed off the derived stage value, not the localized label — so presentation
 * stays correct regardless of locale (see document-vault spec's localized-labels requirement).
 */
export const VAULT_STAGE_BADGE_CLASS: Record<VaultDisplayStage, string> = {
  READY_TO_IMPORT: "bg-primary/10 text-primary",
  NEEDS_REVIEW: "bg-yellow-500/10 text-yellow-600 dark:text-yellow-400",
  PROCESSING: "bg-yellow-500/10 text-yellow-600 dark:text-yellow-400",
  IMPORTED: "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400",
  NOT_PROCESSED: "bg-secondary text-muted-foreground",
  STORED: "bg-secondary text-muted-foreground",
  FAILED: "bg-destructive/10 text-destructive",
  DISMISSED: "bg-secondary text-muted-foreground",
};
