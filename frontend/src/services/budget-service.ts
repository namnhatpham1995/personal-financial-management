import { apiClient } from "@/lib/api-client";

export interface Budget {
  id: number;
  categoryId: number;
  categoryName: string;
  amountLimit: string;
  period: "MONTHLY" | "YEARLY";
  startDate: string;
  currency: string;
  spent: string;
  remaining: string;
  percentUsed: string;
  overBudget: boolean;
}

export interface CreateBudgetPayload {
  categoryId: number;
  period: string;
  amountLimit: string;
  startDate: string;
  currency: string;
}

export const budgetService = {
  list: () => apiClient.get<Budget[]>("/budgets").then((r) => r.data),
  get: (id: number) => apiClient.get<Budget>(`/budgets/${id}`).then((r) => r.data),
  create: (data: CreateBudgetPayload, idempotencyKey: string) =>
    apiClient
      .post<Budget>("/budgets", data, { headers: { "Idempotency-Key": idempotencyKey } })
      .then((r) => r.data),
  update: (id: number, data: Partial<CreateBudgetPayload>) =>
    apiClient.put<Budget>(`/budgets/${id}`, data).then((r) => r.data),
  delete: (id: number) => apiClient.delete(`/budgets/${id}`),
};
