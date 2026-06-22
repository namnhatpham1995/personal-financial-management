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
  currency: string;
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

export interface RateUsed {
  from: string;
  to: string;
  rate: number;
}

export interface ExcludedCurrency {
  currency: string;
  nativeAmount: number;
}

export interface ConvertedSpending {
  categoryId: number;
  categoryName: string;
  totalAmount: number;
  transactionCount: number;
}

export interface ConvertedTrend {
  year: number;
  month: number;
  income: number;
  expense: number;
  net: number;
}

export interface ConvertedOverview {
  targetCurrency: string;
  netWorth: number;
  totalAssets: number;
  totalLiabilities: number;
  spending: ConvertedSpending[];
  trend: ConvertedTrend[];
  rates: RateUsed[];
  asOf: string; // ISO instant string
  ratesUnavailable: boolean;
  stale: boolean;
  excludedCurrencies: ExcludedCurrency[];
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

  getOverview: (targetCurrency: string, from: string, to: string): Promise<ConvertedOverview> =>
    apiClient
      .get<ConvertedOverview>("/analytics/overview", {
        params: { targetCurrency, from, to },
      })
      .then((r) => r.data),
};
