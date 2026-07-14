import { describe, expect, it } from "vitest";
import {
  convertedSpendingToSpendingByCategory,
  convertedTrendToIncomeExpenseTrend,
} from "@/lib/converted-overview-adapters";
import type { ConvertedSpending, ConvertedTrend } from "@/services/analytics-service";

describe("convertedTrendToIncomeExpenseTrend", () => {
  it("maps numeric ConvertedOverview trend rows to string-based IncomeExpenseTrend rows", () => {
    const trend: ConvertedTrend[] = [
      { year: 2026, month: 6, income: 4200.5, expense: 1830.25, net: 2370.25 },
    ];

    expect(convertedTrendToIncomeExpenseTrend(trend, "EUR")).toEqual([
      {
        currency: "EUR",
        year: 2026,
        month: 6,
        totalIncome: "4200.5",
        totalExpense: "1830.25",
        net: "2370.25",
      },
    ]);
  });

  it("returns an empty array for empty input", () => {
    expect(convertedTrendToIncomeExpenseTrend([], "USD")).toEqual([]);
  });
});

describe("convertedSpendingToSpendingByCategory", () => {
  it("maps numeric ConvertedOverview spending rows to string-based SpendingByCategory rows", () => {
    const spending: ConvertedSpending[] = [
      { categoryId: 7, categoryName: "Groceries", totalAmount: 512.4, transactionCount: 12 },
    ];

    expect(convertedSpendingToSpendingByCategory(spending, "USD")).toEqual([
      {
        currency: "USD",
        categoryId: 7,
        categoryName: "Groceries",
        total: "512.4",
        transactionCount: 12,
      },
    ]);
  });

  it("returns an empty array for empty input", () => {
    expect(convertedSpendingToSpendingByCategory([], "USD")).toEqual([]);
  });
});
