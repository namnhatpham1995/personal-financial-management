import { apiClient } from "@/lib/api-client";

export interface SpendingByCategory {
  currency: string;
  categoryId: number;
  categoryName: string;
  total: string;
  transactionCount: number;
}

export interface IncomeExpenseTrend {
  currency: string;
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

export interface AccountBalanceSummary {
  accountId: number;
  accountName: string;
  accountType: string;
  balance: string;
}

/** Per-currency net worth bucket returned by GET /analytics/net-worth */
export interface CurrencyNetWorth {
  currency: string;
  totalAssets: string;
  totalLiabilities: string;
  netWorth: string;
  accounts: AccountBalanceSummary[];
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

  netWorth: () => apiClient.get<CurrencyNetWorth[]>("/analytics/net-worth").then((r) => r.data),
};
