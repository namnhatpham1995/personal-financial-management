import { apiClient } from "@/lib/api-client";

export interface RecurringTransaction {
  id: number;
  accountId: number;
  accountName: string;
  categoryId?: number;
  categoryName?: string;
  transactionType: "INCOME" | "EXPENSE" | "TRANSFER";
  amount: string;
  frequency: string;
  intervalValue: number;
  startDate: string;
  endDate?: string;
  maxOccurrences?: number;
  nextRunDate: string;
  occurrencesCount: number;
  active: boolean;
  note?: string;
}

export interface CreateRecurringPayload {
  accountId: number;
  categoryId?: number;
  transactionType: string;
  amount: string;
  frequency: string;
  intervalValue: number;
  startDate: string;
  endDate?: string;
  maxOccurrences?: number;
  note?: string;
}

export const recurringService = {
  list: () => apiClient.get<RecurringTransaction[]>("/recurring-transactions").then((r) => r.data),
  create: (data: CreateRecurringPayload, idempotencyKey: string) =>
    apiClient
      .post<RecurringTransaction>("/recurring-transactions", data, {
        headers: { "Idempotency-Key": idempotencyKey },
      })
      .then((r) => r.data),
  pause: (id: number) =>
    apiClient.post<RecurringTransaction>(`/recurring-transactions/${id}/pause`).then((r) => r.data),
  resume: (id: number) =>
    apiClient.post<RecurringTransaction>(`/recurring-transactions/${id}/resume`).then((r) => r.data),
  delete: (id: number) => apiClient.delete(`/recurring-transactions/${id}`),
};
