import { apiClient, VAULT_BASE_URL } from "@/lib/api-client";
import type { AgentRunStatus } from "@/services/agent-run-service";

export type VaultDocumentType = "RECEIPT" | "STATEMENT";
export type VaultDocumentStatus = "UPLOADED" | "STAGED" | "CONFIRMING" | "ACTIVE";

/** Derived lifecycle stage — mirrors the backend's VaultStage. See lib/vault-stage.ts for derivation. */
export type VaultStage =
  | "READY_TO_IMPORT"
  | "NEEDS_REVIEW"
  | "PROCESSING"
  | "IMPORTED"
  | "NOT_PROCESSED"
  | "FAILED"
  | "DISMISSED";

export interface VaultDocument {
  id: string;
  accountId?: number;
  type: VaultDocumentType;
  status: VaultDocumentStatus;
  source: string;
  capturedAt: string;
  payload?: Record<string, unknown>;
  hasBinary: boolean;
  originalFilename?: string;
  transactionId?: number;
  /** Status of the most recent ingestion run for this receipt, or null/undefined if never ingested. */
  ingestionStatus?: AgentRunStatus | null;
}

export interface VaultReassignmentPreview {
  documentId: string;
  type: VaultDocumentType;
  originalFilename?: string;
  sourceAccountId?: number;
  sourceAccountName?: string;
  sourceCurrency?: string;
  targetAccountId: number;
  targetAccountName: string;
  targetCurrency: string;
  currencyChanged: boolean;
  importedTransactionCount: number;
  detachManualLink: boolean;
}

export interface VaultReassignmentResult {
  document: VaultDocument;
  removedImportedTransactions: number;
  manualLinkDetached: boolean;
  replayed: boolean;
}

export interface StagedRow {
  date: string;
  amount: string;
  type: "INCOME" | "EXPENSE";
  description: string;
  dedupKey: string;
  /** Category text as parsed from the source file (e.g. a CSV category column), if any. */
  category?: string | null;
  /** Existing category suggested by matching `category` to the row's transaction type; null if no match. */
  categoryId?: number | null;
}

export interface ConfirmImportRow {
  dedupKey: string;
  categoryId: number | null;
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

/** Per-stage counts across the whole matching collection, not just one page. Absent stages have zero matches. */
export type VaultStageCounts = Partial<Record<VaultStage, number>>;

export interface WorkspaceListParams {
  types?: VaultDocumentType[];
  stages?: VaultStage[];
  accountId?: number;
  page?: number;
  size?: number;
}

export interface WorkspaceCountsParams {
  types?: VaultDocumentType[];
  accountId?: number;
}

/**
 * Repeated-key array serialization ({@code type=A&type=B}, no brackets) — axios's default
 * bracket notation ({@code type[]=A}) does not bind to Spring's `Set<T>` query params.
 */
const REPEATED_KEY_PARAMS = { indexes: null } as const;

export const vaultService = {
  /** Upload a receipt or statement binary for an account. Returns the new vault document (status UPLOADED). */
  async upload(type: VaultDocumentType, accountId: number, file: File, idempotencyKey: string): Promise<VaultDocument> {
    const form = new FormData();
    form.append("type", type);
    form.append("accountId", String(accountId));
    form.append("file", file);
    const { data } = await apiClient.post<VaultDocument>("/vault/upload", form, {
      baseURL: VAULT_BASE_URL,
      // apiClient defaults to Content-Type: application/json for every request; for a
      // FormData body that header must be cleared so axios/the browser can set the real
      // multipart/form-data boundary instead — otherwise the backend rejects the request.
      headers: { "Content-Type": undefined, "Idempotency-Key": idempotencyKey },
    });
    return data;
  },

  async list(page = 0, size = 20): Promise<PageResponse<VaultDocument>> {
    const { data } = await apiClient.get<PageResponse<VaultDocument>>("/vault", {
      baseURL: VAULT_BASE_URL,
      params: { page, size },
    });
    return data;
  },

  /** Lists one document type overall, or narrowed to one account when accountId is supplied. */
  async listByType(
    type: VaultDocumentType,
    accountId?: number,
    page = 0,
    size = 20
  ): Promise<PageResponse<VaultDocument>> {
    const { data } = await apiClient.get<PageResponse<VaultDocument>>("/vault", {
      baseURL: VAULT_BASE_URL,
      params: { type, ...(accountId == null ? {} : { accountId }), page, size },
    });
    return data;
  },

  /** Lists vault documents for one account and type — backs the per-account Statement/Receipt tabs. */
  async listByAccount(
    accountId: number,
    type: VaultDocumentType,
    page = 0,
    size = 20
  ): Promise<PageResponse<VaultDocument>> {
    const { data } = await apiClient.get<PageResponse<VaultDocument>>("/vault/by-account", {
      baseURL: VAULT_BASE_URL,
      params: { accountId, type, page, size },
    });
    return data;
  },

  /** Lists documents across optional types and derived stages — backs the unified Vault workspace. */
  async workspace(params: WorkspaceListParams = {}): Promise<PageResponse<VaultDocument>> {
    const { types, stages, accountId, page = 0, size = 20 } = params;
    const { data } = await apiClient.get<PageResponse<VaultDocument>>("/vault/workspace", {
      baseURL: VAULT_BASE_URL,
      params: { type: types, stage: stages, accountId, page, size },
      paramsSerializer: REPEATED_KEY_PARAMS,
    });
    return data;
  },

  /** Per-stage document counts across the whole matching collection, for filter badges. */
  async workspaceStageCounts(params: WorkspaceCountsParams = {}): Promise<VaultStageCounts> {
    const { types, accountId } = params;
    const { data } = await apiClient.get<{ counts: VaultStageCounts }>("/vault/workspace/counts", {
      baseURL: VAULT_BASE_URL,
      params: { type: types, accountId },
      paramsSerializer: REPEATED_KEY_PARAMS,
    });
    return data.counts;
  },

  async previewReassignment(documentId: string, targetAccountId: number): Promise<VaultReassignmentPreview> {
    const { data } = await apiClient.get<VaultReassignmentPreview>(
      `/vault/${documentId}/reassignment-preview`,
      { baseURL: VAULT_BASE_URL, params: { targetAccountId } }
    );
    return data;
  },

  async reassign(
    documentId: string,
    targetAccountId: number,
    idempotencyKey: string
  ): Promise<VaultReassignmentResult> {
    const { data } = await apiClient.post<VaultReassignmentResult>(
      `/vault/${documentId}/reassign`,
      { targetAccountId },
      { baseURL: VAULT_BASE_URL, headers: { "Idempotency-Key": idempotencyKey } }
    );
    return data;
  },

  /** Lists legacy receipts uploaded before account assignment became mandatory. */
  async listUnassignedReceipts(page = 0, size = 20): Promise<PageResponse<VaultDocument>> {
    const { data } = await apiClient.get<PageResponse<VaultDocument>>("/vault/unassigned-receipts", {
      baseURL: VAULT_BASE_URL,
      params: { page, size },
    });
    return data;
  },

  /** Permanently assigns an account-less legacy receipt to one owned account. */
  async assignAccount(id: string, accountId: number): Promise<VaultDocument> {
    const { data } = await apiClient.patch<VaultDocument>(`/vault/${id}/account`, null, {
      baseURL: VAULT_BASE_URL,
      params: { accountId },
    });
    return data;
  },

  async getById(id: string): Promise<VaultDocument> {
    const { data } = await apiClient.get<VaultDocument>(`/vault/${id}`, { baseURL: VAULT_BASE_URL });
    return data;
  },

  /** Returns a blob URL for the stored binary. Caller must revoke it when done. */
  async getDownloadUrl(id: string): Promise<string> {
    const { data } = await apiClient.get<Blob>(`/vault/${id}/download`, {
      baseURL: VAULT_BASE_URL,
      responseType: "blob",
    });
    return URL.createObjectURL(data);
  },

  /** Fetches the stored binary for in-browser reading (not a forced download). */
  async getInlineContent(id: string): Promise<{ blob: Blob; contentType: string }> {
    const response = await apiClient.get<Blob>(`/vault/${id}/download`, {
      baseURL: VAULT_BASE_URL,
      params: { disposition: "inline" },
      responseType: "blob",
    });
    const rawContentType = response.headers["content-type"];
    const contentType =
      (typeof rawContentType === "string" ? rawContentType : undefined) ??
      response.data.type ??
      "application/octet-stream";
    return { blob: response.data, contentType };
  },

  async linkToTransaction(id: string, transactionId: number): Promise<VaultDocument> {
    const { data } = await apiClient.patch<VaultDocument>(`/vault/${id}/link`, null, {
      baseURL: VAULT_BASE_URL,
      params: { transactionId },
    });
    return data;
  },

  async byTransactionIds(transactionIds: number[]): Promise<VaultDocument[]> {
    const { data } = await apiClient.post<VaultDocument[]>("/vault/by-transactions", transactionIds, {
      baseURL: VAULT_BASE_URL,
    });
    return data;
  },

  async deleteById(id: string): Promise<void> {
    await apiClient.delete(`/vault/${id}`, { baseURL: VAULT_BASE_URL });
  },

  // ── statement import ──────────────────────────────────────────────────────

  async importUpload(accountId: number, file: File, idempotencyKey: string): Promise<string> {
    const form = new FormData();
    form.append("accountId", String(accountId));
    form.append("file", file);
    const { data } = await apiClient.post<{ documentId: string }>("/vault/import/upload", form, {
      baseURL: VAULT_BASE_URL,
      // Same Content-Type override as upload() above — required for any FormData body.
      headers: { "Content-Type": undefined, "Idempotency-Key": idempotencyKey },
    });
    return data.documentId;
  },

  /** Parses an UPLOADED statement on demand, transitioning it to STAGED. Safe to re-run. */
  async parseImport(documentId: string): Promise<StagedRow[]> {
    const { data } = await apiClient.post<StagedRow[]>(`/vault/import/${documentId}/parse`, null, {
      baseURL: VAULT_BASE_URL,
    });
    return data;
  },

  async getImportRows(documentId: string): Promise<StagedRow[]> {
    const { data } = await apiClient.get<StagedRow[]>(`/vault/import/${documentId}/rows`, {
      baseURL: VAULT_BASE_URL,
    });
    return data;
  },

  /**
   * Previously parsed statement rows for read-only in-browser preview, reachable at any
   * lifecycle status (unlike getImportRows, which is scoped to the STAGED review workflow).
   * Rejects with a 404 if the statement has never been parsed.
   */
  async getPreviewRows(documentId: string): Promise<StagedRow[]> {
    const { data } = await apiClient.get<StagedRow[]>(`/vault/${documentId}/preview-rows`, {
      baseURL: VAULT_BASE_URL,
    });
    return data;
  },

  async confirmImport(
    documentId: string,
    rows: ConfirmImportRow[],
    idempotencyKey: string
  ): Promise<number> {
    const { data } = await apiClient.post<{ created: number }>(
      `/vault/import/${documentId}/confirm`,
      { rows },
      { baseURL: VAULT_BASE_URL, headers: { "Idempotency-Key": idempotencyKey } }
    );
    return data.created;
  },
};
