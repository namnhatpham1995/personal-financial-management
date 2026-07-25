import { apiClient, VAULT_BASE_URL } from "@/lib/api-client";
import type { AgentRunStatus } from "@/services/agent-run-service";

export type VaultDocumentType = "RECEIPT" | "STATEMENT";
export type VaultDocumentStatus = "UPLOADED" | "STAGED" | "CONFIRMING" | "ACTIVE";

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
