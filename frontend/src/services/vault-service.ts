import { apiClient, VAULT_BASE_URL } from "@/lib/api-client";
import type { AgentRunStatus } from "@/services/agent-run-service";

export type VaultDocumentType = "RECEIPT" | "STATEMENT";
export type VaultDocumentStatus = "STAGED" | "ACTIVE";

export interface VaultDocument {
  id: string;
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
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

export const vaultService = {
  /** Upload a receipt or statement binary. Returns the new vault document. */
  async upload(type: VaultDocumentType, file: File, idempotencyKey: string): Promise<VaultDocument> {
    const form = new FormData();
    form.append("type", type);
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

  async getImportRows(documentId: string): Promise<StagedRow[]> {
    const { data } = await apiClient.get<StagedRow[]>(`/vault/import/${documentId}/rows`, {
      baseURL: VAULT_BASE_URL,
    });
    return data;
  },

  async confirmImport(
    documentId: string,
    selectedDedupKeys: string[],
    idempotencyKey: string
  ): Promise<number> {
    const { data } = await apiClient.post<{ created: number }>(
      `/vault/import/${documentId}/confirm`,
      { selectedDedupKeys },
      { baseURL: VAULT_BASE_URL, headers: { "Idempotency-Key": idempotencyKey } }
    );
    return data.created;
  },
};
