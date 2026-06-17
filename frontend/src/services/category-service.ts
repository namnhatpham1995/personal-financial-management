import { apiClient } from "@/lib/api-client";

export interface Category {
  id: number;
  name: string;
  transactionType: "INCOME" | "EXPENSE" | "TRANSFER";
  system: boolean;
}

export const categoryService = {
  list: () => apiClient.get<Category[]>("/categories").then((r) => r.data),
};
