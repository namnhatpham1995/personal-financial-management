import { apiClient } from "@/lib/api-client";

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
  async upload(type: VaultDocumentType, file: File): Promise<VaultDocument> {
    const form = new FormData();
    form.append("type", type);
    form.append("file", file);
    const { data } = await apiClient.post<VaultDocument>("/vault/upload", form);
    return data;
  },

  async list(page = 0, size = 20): Promise<PageResponse<VaultDocument>> {
    const { data } = await apiClient.get<PageResponse<VaultDocument>>("/vault", {
      params: { page, size },
    });
    return data;
  },

  async getById(id: string): Promise<VaultDocument> {
    const { data } = await apiClient.get<VaultDocument>(`/vault/${id}`);
    return data;
  },

  /** Returns a blob URL for the stored binary. Caller must revoke it when done. */
  async getDownloadUrl(id: string): Promise<string> {
    const { data } = await apiClient.get<Blob>(`/vault/${id}/download`, {
      responseType: "blob",
    });
    return URL.createObjectURL(data);
  },

  async linkToTransaction(id: string, transactionId: number): Promise<VaultDocument> {
    const { data } = await apiClient.patch<VaultDocument>(`/vault/${id}/link`, null, {
      params: { transactionId },
    });
    return data;
  },

  async byTransactionIds(transactionIds: number[]): Promise<VaultDocument[]> {
    const { data } = await apiClient.post<VaultDocument[]>("/vault/by-transactions", transactionIds);
    return data;
  },

  async deleteById(id: string): Promise<void> {
    await apiClient.delete(`/vault/${id}`);
  },

  // ── statement import ──────────────────────────────────────────────────────

  async importUpload(accountId: number, file: File): Promise<string> {
    const form = new FormData();
    form.append("accountId", String(accountId));
    form.append("file", file);
    const { data } = await apiClient.post<{ documentId: string }>("/vault/import/upload", form);
    return data.documentId;
  },

  async getImportRows(documentId: string): Promise<StagedRow[]> {
    const { data } = await apiClient.get<StagedRow[]>(`/vault/import/${documentId}/rows`);
    return data;
  },

  async confirmImport(
    documentId: string,
    selectedDedupKeys: string[]
  ): Promise<number> {
    const { data } = await apiClient.post<{ created: number }>(
      `/vault/import/${documentId}/confirm`,
      { selectedDedupKeys }
    );
    return data.created;
  },
};
