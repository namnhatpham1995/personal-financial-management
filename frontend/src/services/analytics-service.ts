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

/** Per-currency balance bucket returned by GET /analytics/balances */
export interface CurrencyBalance {
  currency: string;
  totalBalance: string;
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
  spending: ConvertedSpending[];
  trend: ConvertedTrend[];
  rates: RateUsed[];
  asOf: string; // ISO instant string
  ratesUnavailable: boolean;
  stale: boolean;
  excludedCurrencies: ExcludedCurrency[];
}

export interface IncomingTransferTotal {
  accountId: number;
  total: string;
}

/** Returned by GET /analytics/balances when targetCurrency is supplied. */
export interface BalancesSummary {
  buckets: CurrencyBalance[];
  targetCurrency: string;
  convertedTotal: string;
  rates: RateUsed[];
  asOf: string | null;
  stale: boolean;
  ratesUnavailable: boolean;
  excludedCurrencies: ExcludedCurrency[];
}

export const analyticsService = {
  /** Spending breakdown by category; pass accountId to scope to a single account. */
  spendingByCategory: (from: string, to: string, accountId?: number) =>
    apiClient
      .get<SpendingByCategory[]>("/analytics/spending-by-category", {
        params: { from, to, ...(accountId != null ? { accountId } : {}) },
      })
      .then((r) => r.data),

  /** Total incoming transfers (account as counterparty) for the range. */
  incomingTransferTotal: (accountId: number, from: string, to: string) =>
    apiClient
      .get<IncomingTransferTotal>("/analytics/incoming-transfer-total", {
        params: { accountId, from, to },
      })
      .then((r) => r.data),

  incomeVsExpense: (from: string, to: string) =>
    apiClient
      .get<IncomeExpenseTrend[]>("/analytics/income-vs-expense", { params: { from, to } })
      .then((r) => r.data),

  budgetProgress: () =>
    apiClient.get<BudgetProgress[]>("/analytics/budget-progress").then((r) => r.data),

  balances: () => apiClient.get<CurrencyBalance[]>("/analytics/balances").then((r) => r.data),

  /** Native per-currency buckets plus a grand total converted into targetCurrency. */
  balancesSummary: (targetCurrency: string) =>
    apiClient
      .get<BalancesSummary>("/analytics/balances", { params: { targetCurrency } })
      .then((r) => r.data),

  getOverview: (targetCurrency: string, from: string, to: string): Promise<ConvertedOverview> =>
    apiClient
      .get<ConvertedOverview>("/analytics/overview", {
        params: { targetCurrency, from, to },
      })
      .then((r) => r.data),
};
