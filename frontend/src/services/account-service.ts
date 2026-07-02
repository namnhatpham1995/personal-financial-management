import { apiClient } from "@/lib/api-client";

export interface Account {
  id: number;
  name: string;
  accountType: string;
  currency: string;
  initialBalance: number;
  currentBalance: string;
  description?: string;
  createdAt: string;
}

export interface CreateAccountPayload {
  name: string;
  accountType: string;
  currency: string;
  initialBalance: number;
  description?: string;
}

export interface UpdateAccountPayload {
  name?: string;
  accountType?: string;
  currency?: string;
  initialBalance?: number;
}

export interface AccountDeletePreview {
  accountId: number;
  transactionCount: number;
}

export const accountService = {
  list: () => apiClient.get<Account[]>("/accounts").then((r) => r.data),
  get: (id: number) => apiClient.get<Account>(`/accounts/${id}`).then((r) => r.data),
  create: (data: CreateAccountPayload) =>
    apiClient.post<Account>("/accounts", data).then((r) => r.data),
  update: (id: number, data: UpdateAccountPayload) =>
    apiClient.put<Account>(`/accounts/${id}`, data).then((r) => r.data),
  deletePreview: (id: number) =>
    apiClient.get<AccountDeletePreview>(`/accounts/${id}/delete-preview`).then((r) => r.data),
  delete: (id: number) => apiClient.delete(`/accounts/${id}`),
};
