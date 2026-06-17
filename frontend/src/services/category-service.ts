import { apiClient } from "@/lib/api-client";

export interface Category {
  id: number;
  name: string;
  transactionType: "INCOME" | "EXPENSE";
  system: boolean;
}

export interface CreateCategoryPayload {
  name: string;
  transactionType: "INCOME" | "EXPENSE";
}

export interface UpdateCategoryPayload {
  name: string;
  transactionType?: "INCOME" | "EXPENSE";
}

export const categoryService = {
  list: () => apiClient.get<Category[]>("/categories").then((r) => r.data),
  create: (data: CreateCategoryPayload) =>
    apiClient.post<Category>("/categories", data).then((r) => r.data),
  update: (id: number, data: UpdateCategoryPayload) =>
    apiClient.put<Category>(`/categories/${id}`, data).then((r) => r.data),
  delete: (id: number) => apiClient.delete(`/categories/${id}`),
};
