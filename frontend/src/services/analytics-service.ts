import { apiClient } from "@/lib/api-client";

export interface SpendingByCategory {
  categoryId: number;
  categoryName: string;
  total: string;
  transactionCount: number;
}

export interface IncomeExpenseTrend {
  year: number;
  month: number;
  totalIncome: string;
  totalExpense: string;
  net: string;
}

export interface BudgetProgress {
  budgetId: number;
  budgetName: string;
  categoryName?: string;
  limitAmount: string;
  spent: string;
  remaining: string;
  percentUsed: string;
  overBudget: boolean;
}

export interface NetWorth {
  totalAssets: string;
  totalLiabilities: string;
  netWorth: string;
  accounts: Array<{
    accountId: number;
    accountName: string;
    accountType: string;
    balance: string;
  }>;
}

export const analyticsService = {
  spendingByCategory: (from: string, to: string) =>
    apiClient
      .get<SpendingByCategory[]>("/analytics/spending-by-category", { params: { from, to } })
      .then((r) => r.data),

  incomeVsExpense: (from: string, to: string) =>
    apiClient
      .get<IncomeExpenseTrend[]>("/analytics/income-vs-expense", { params: { from, to } })
      .then((r) => r.data),

  budgetProgress: () =>
    apiClient.get<BudgetProgress[]>("/analytics/budget-progress").then((r) => r.data),

  netWorth: () => apiClient.get<NetWorth>("/analytics/net-worth").then((r) => r.data),
};
