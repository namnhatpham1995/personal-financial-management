import { apiClient } from "@/lib/api-client";

export interface Account {
  id: number;
  name: string;
  accountType: string;
  currency: string;
  currentBalance: string;
  description?: string;
  createdAt: string;
}

export interface CreateAccountPayload {
  name: string;
  accountType: string;
  currency: string;
  initialBalance: string;
  description?: string;
}

export const accountService = {
  list: () => apiClient.get<Account[]>("/accounts").then((r) => r.data),
  get: (id: number) => apiClient.get<Account>(`/accounts/${id}`).then((r) => r.data),
  create: (data: CreateAccountPayload) =>
    apiClient.post<Account>("/accounts", data).then((r) => r.data),
  update: (id: number, data: Partial<CreateAccountPayload>) =>
    apiClient.put<Account>(`/accounts/${id}`, data).then((r) => r.data),
  delete: (id: number) => apiClient.delete(`/accounts/${id}`),
};
