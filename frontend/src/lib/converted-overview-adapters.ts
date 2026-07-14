import type {
  ConvertedSpending,
  ConvertedTrend,
  IncomeExpenseTrend,
  SpendingByCategory,
} from "@/services/analytics-service";

/** Adapts numeric ConvertedOverview trend rows to the string-based shape CashFlowChart expects. */
export function convertedTrendToIncomeExpenseTrend(
  trend: ConvertedTrend[],
  currency: string
): IncomeExpenseTrend[] {
  return trend.map((t) => ({
    currency,
    year: t.year,
    month: t.month,
    totalIncome: String(t.income),
    totalExpense: String(t.expense),
    net: String(t.net),
  }));
}

/** Adapts numeric ConvertedOverview spending rows to the string-based shape SpendingDonutChart expects. */
export function convertedSpendingToSpendingByCategory(
  spending: ConvertedSpending[],
  currency: string
): SpendingByCategory[] {
  return spending.map((s) => ({
    currency,
    categoryId: s.categoryId,
    categoryName: s.categoryName,
    total: String(s.totalAmount),
    transactionCount: s.transactionCount,
  }));
}
