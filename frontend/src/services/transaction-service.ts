import { apiClient } from "@/lib/api-client";

export interface Transaction {
  id: number;
  accountId: number;
  accountName: string;
  currency: string;
  categoryId?: number;
  categoryName?: string;
  transactionType: "INCOME" | "EXPENSE" | "TRANSFER";
  amount: string;
  transactionDate: string;
  note?: string;
  transferAccountId?: number;
  transferAccountName?: string;
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

export interface TransactionFilters {
  accountId?: number;
  categoryId?: number;
  type?: string;
  startDate?: string;
  endDate?: string;
  note?: string;
  page?: number;
  size?: number;
  sortBy?: string;
  sortDir?: string;
}

export interface CreateTransactionPayload {
  accountId: number;
  categoryId?: number;
  transactionType: string;
  amount: string;
  transactionDate: string;
  note?: string;
  transferAccountId?: number;
}

/** Fields accepted by PUT /transactions/{id} — type and account are immutable */
export interface UpdateTransactionPayload {
  amount?: string;
  transactionDate?: string;
  categoryId?: number;
  note?: string;
}

export const transactionService = {
  list: (filters: TransactionFilters = {}) =>
    apiClient
      .get<PageResponse<Transaction>>("/transactions", { params: filters })
      .then((r) => r.data),
  get: (id: number) => apiClient.get<Transaction>(`/transactions/${id}`).then((r) => r.data),
  create: (data: CreateTransactionPayload) =>
    apiClient.post<Transaction>("/transactions", data).then((r) => r.data),
  update: (id: number, data: UpdateTransactionPayload) =>
    apiClient.put<Transaction>(`/transactions/${id}`, data).then((r) => r.data),
  delete: (id: number) => apiClient.delete(`/transactions/${id}`),
};
