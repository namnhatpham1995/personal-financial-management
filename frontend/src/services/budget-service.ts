import { apiClient } from "@/lib/api-client";

export interface Budget {
  id: number;
  name: string;
  categoryId?: number;
  categoryName?: string;
  limitAmount: string;
  period: "MONTHLY" | "YEARLY";
  spent: string;
  remaining: string;
  percentUsed: string;
  overBudget: boolean;
}

export interface CreateBudgetPayload {
  name: string;
  categoryId?: number;
  limitAmount: string;
  period: string;
}

export const budgetService = {
  list: () => apiClient.get<Budget[]>("/budgets").then((r) => r.data),
  get: (id: number) => apiClient.get<Budget>(`/budgets/${id}`).then((r) => r.data),
  create: (data: CreateBudgetPayload) =>
    apiClient.post<Budget>("/budgets", data).then((r) => r.data),
  update: (id: number, data: Partial<CreateBudgetPayload>) =>
    apiClient.put<Budget>(`/budgets/${id}`, data).then((r) => r.data),
  delete: (id: number) => apiClient.delete(`/budgets/${id}`),
};
