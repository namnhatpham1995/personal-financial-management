import { describe, it, expect } from "vitest";
import {
  deriveStatementStage,
  deriveReceiptStage,
  deriveDisplayStage,
  NEEDS_ATTENTION_STAGES,
  VAULT_STAGE_BADGE_CLASS,
} from "@/lib/vault-stage";
import type { VaultDocument, VaultDocumentStatus } from "@/services/vault-service";
import type { AgentRunStatus } from "@/services/agent-run-service";

const STATEMENT_STATUSES: VaultDocumentStatus[] = ["UPLOADED", "STAGED", "CONFIRMING", "ACTIVE"];
const INGESTION_STATUSES: AgentRunStatus[] = [
  "EXTRACTING",
  "AWAITING_REVIEW",
  "COMMITTED",
  "REJECTED",
  "FAILED",
  "INVALIDATED",
];

function receiptDoc(overrides: Partial<VaultDocument> = {}): VaultDocument {
  return {
    id: "doc-1",
    type: "RECEIPT",
    status: "UPLOADED",
    source: "manual",
    capturedAt: "2026-01-01T00:00:00Z",
    hasBinary: true,
    ...overrides,
  };
}

function statementDoc(status: VaultDocumentStatus): VaultDocument {
  return {
    id: "doc-1",
    type: "STATEMENT",
    status,
    source: "csv",
    capturedAt: "2026-01-01T00:00:00Z",
    hasBinary: true,
  };
}

describe("deriveStatementStage", () => {
  it.each(STATEMENT_STATUSES)("maps every statement status to a stage: %s", (status) => {
    expect(deriveStatementStage(status)).toBeTruthy();
  });

  it("maps known statuses to their expected stage", () => {
    expect(deriveStatementStage("UPLOADED")).toBe("READY_TO_IMPORT");
    expect(deriveStatementStage("STAGED")).toBe("NEEDS_REVIEW");
    expect(deriveStatementStage("CONFIRMING")).toBe("NEEDS_REVIEW");
    expect(deriveStatementStage("ACTIVE")).toBe("IMPORTED");
  });
});

describe("deriveReceiptStage", () => {
  it.each(INGESTION_STATUSES)("maps every ingestion status to a stage: %s", (status) => {
    expect(deriveReceiptStage(status, false)).toBeTruthy();
  });

  it("maps known statuses to their expected stage", () => {
    expect(deriveReceiptStage("EXTRACTING", false)).toBe("PROCESSING");
    expect(deriveReceiptStage("AWAITING_REVIEW", false)).toBe("NEEDS_REVIEW");
    expect(deriveReceiptStage("COMMITTED", false)).toBe("IMPORTED");
    expect(deriveReceiptStage("FAILED", false)).toBe("FAILED");
    expect(deriveReceiptStage("REJECTED", false)).toBe("DISMISSED");
    expect(deriveReceiptStage("INVALIDATED", false)).toBe("DISMISSED");
  });

  it("never-ingested receipt resolves to NOT_PROCESSED when the agent service is available", () => {
    expect(deriveReceiptStage(null, false)).toBe("NOT_PROCESSED");
    expect(deriveReceiptStage(undefined, false)).toBe("NOT_PROCESSED");
  });

  it("never-ingested receipt resolves to STORED when the agent service is unavailable", () => {
    expect(deriveReceiptStage(null, true)).toBe("STORED");
  });

  it("an already-ingested receipt keeps its real stage even if the agent service later becomes unavailable", () => {
    expect(deriveReceiptStage("COMMITTED", true)).toBe("IMPORTED");
  });
});

describe("deriveDisplayStage", () => {
  it("routes statements through the statement mapping", () => {
    expect(deriveDisplayStage(statementDoc("STAGED"), false)).toBe("NEEDS_REVIEW");
  });

  it("routes receipts through the receipt mapping", () => {
    expect(deriveDisplayStage(receiptDoc({ ingestionStatus: "AWAITING_REVIEW" }), false)).toBe("NEEDS_REVIEW");
  });

  it("degrades a never-ingested receipt to STORED when the agent service is unavailable", () => {
    expect(deriveDisplayStage(receiptDoc({ ingestionStatus: null }), true)).toBe("STORED");
  });
});

describe("NEEDS_ATTENTION_STAGES and VAULT_STAGE_BADGE_CLASS", () => {
  it("needs-attention set matches the design brief: ready to import, needs review, failed", () => {
    expect(NEEDS_ATTENTION_STAGES).toEqual(["READY_TO_IMPORT", "NEEDS_REVIEW", "FAILED"]);
  });

  it("every possible display stage has a badge class, keyed off the stage value", () => {
    const allDisplayStages = [
      ...STATEMENT_STATUSES.map(deriveStatementStage),
      ...INGESTION_STATUSES.map((s) => deriveReceiptStage(s, false)),
      "NOT_PROCESSED",
      "STORED",
    ] as const;
    for (const stage of allDisplayStages) {
      expect(VAULT_STAGE_BADGE_CLASS[stage]).toBeTruthy();
    }
  });
});
