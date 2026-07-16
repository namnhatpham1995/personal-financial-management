import { screen, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { describe, expect, it, vi, beforeEach } from "vitest";
import DashboardPage from "@/app/dashboard/page";
import { accountService, type Account } from "@/services/account-service";
import { transactionService } from "@/services/transaction-service";
import { analyticsService } from "@/services/analytics-service";
import { budgetService } from "@/services/budget-service";
import { categoryService } from "@/services/category-service";
import { renderWithIntl as render } from "@/test/test-utils";

vi.mock("@/services/account-service");
vi.mock("@/services/transaction-service");
vi.mock("@/services/analytics-service");
vi.mock("@/services/budget-service");
vi.mock("@/services/category-service");

const usdAccount: Account = {
  id: 1,
  name: "Checking",
  accountType: "BANK",
  currency: "USD",
  initialBalance: 100,
  currentBalance: "1000.00",
  createdAt: "2026-01-01T00:00:00Z",
};

const eurAccount: Account = {
  id: 2,
  name: "Girokonto",
  accountType: "BANK",
  currency: "EUR",
  initialBalance: 50,
  currentBalance: "500.00",
  createdAt: "2026-01-01T00:00:00Z",
};

function renderDashboard() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <DashboardPage />
    </QueryClientProvider>
  );
}

function stubEmptyContent() {
  vi.mocked(transactionService.list).mockResolvedValue({
    content: [],
    totalElements: 0,
    totalPages: 1,
    number: 0,
    size: 5,
  });
  vi.mocked(budgetService.list).mockResolvedValue([]);
  vi.mocked(categoryService.list).mockResolvedValue([]);
}

describe("DashboardPage currency tiers", () => {
  beforeEach(() => {
    stubEmptyContent();
  });

  it("renders one summary card per footprint currency, including a budget-only currency with no accounts", async () => {
    vi.mocked(accountService.list).mockResolvedValue([usdAccount, eurAccount]);
    vi.mocked(analyticsService.balances).mockResolvedValue([
      { currency: "USD", totalBalance: "1000.00", accounts: [] },
      { currency: "EUR", totalBalance: "500.00", accounts: [] },
    ]);
    vi.mocked(analyticsService.balancesSummary).mockResolvedValue({
      buckets: [],
      targetCurrency: "USD",
      convertedTotal: "1500.00",
      rates: [],
      asOf: null,
      stale: false,
      ratesUnavailable: false,
      excludedCurrencies: [],
    });
    vi.mocked(analyticsService.incomeVsExpense).mockResolvedValue([]);
    vi.mocked(analyticsService.spendingByCategory).mockResolvedValue([]);
    // VND has a budget but no accounts or transactions — still gets a card.
    vi.mocked(analyticsService.budgetProgress).mockResolvedValue([
      {
        budgetId: 1,
        budgetName: "Groceries",
        currency: "VND",
        limitAmount: "1000000",
        spent: "0",
        remaining: "1000000",
        percentUsed: "0",
        overBudget: false,
      },
    ]);
    vi.mocked(analyticsService.getOverview).mockResolvedValue({
      targetCurrency: "USD",
      spending: [],
      trend: [],
      rates: [],
      asOf: new Date().toISOString(),
      ratesUnavailable: false,
      stale: false,
      excludedCurrencies: [],
    });

    renderDashboard();

    const usdCard = await screen.findByRole("link", { name: /USD/ });
    expect(within(usdCard).getByText("1,000.00 USD")).toBeInTheDocument();

    const eurCard = screen.getByRole("link", { name: /EUR/ });
    expect(within(eurCard).getByText("500.00 EUR")).toBeInTheDocument();

    // VND has a budget but no accounts — still gets a card, with no native total.
    const vndCard = screen.getByRole("link", { name: /VND/ });
    expect(within(vndCard).getByText("No accounts")).toBeInTheDocument();

    // Multi-currency: no per-currency charts/account boxes inline (Tier 3 content only).
    expect(screen.queryByText("Cash Flow", { selector: "h3" })).not.toBeInTheDocument();
  });

  it("collapses to full inline detail — no summary cards — for a single-currency user", async () => {
    vi.mocked(accountService.list).mockResolvedValue([usdAccount]);
    vi.mocked(analyticsService.balances).mockResolvedValue([
      { currency: "USD", totalBalance: "1000.00", accounts: [] },
    ]);
    vi.mocked(analyticsService.incomeVsExpense).mockResolvedValue([]);
    vi.mocked(analyticsService.spendingByCategory).mockResolvedValue([]);
    vi.mocked(analyticsService.budgetProgress).mockResolvedValue([]);

    renderDashboard();

    expect(await screen.findByText("Checking")).toBeInTheDocument();
    // Inline detail body renders the full charts row directly — not a summary card link.
    expect(screen.getByRole("heading", { name: "Cash Flow" })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /USD/ })).not.toBeInTheDocument();
  });

  it("shows the rates-unavailable notice while native summary cards keep rendering", async () => {
    vi.mocked(accountService.list).mockResolvedValue([usdAccount, eurAccount]);
    vi.mocked(analyticsService.balances).mockResolvedValue([
      { currency: "USD", totalBalance: "1000.00", accounts: [] },
      { currency: "EUR", totalBalance: "500.00", accounts: [] },
    ]);
    vi.mocked(analyticsService.balancesSummary).mockResolvedValue({
      buckets: [],
      targetCurrency: "USD",
      convertedTotal: "0",
      rates: [],
      asOf: null,
      stale: false,
      ratesUnavailable: true,
      excludedCurrencies: [],
    });
    vi.mocked(analyticsService.incomeVsExpense).mockResolvedValue([]);
    vi.mocked(analyticsService.spendingByCategory).mockResolvedValue([]);
    vi.mocked(analyticsService.budgetProgress).mockResolvedValue([]);
    vi.mocked(analyticsService.getOverview).mockResolvedValue({
      targetCurrency: "USD",
      spending: [],
      trend: [],
      rates: [],
      asOf: new Date().toISOString(),
      ratesUnavailable: true,
      stale: false,
      excludedCurrencies: [],
    });

    renderDashboard();

    expect(
      await screen.findByText("Live rates unavailable — showing per-currency figures below.")
    ).toBeInTheDocument();

    const usdCard = screen.getByRole("link", { name: /USD/ });
    expect(within(usdCard).getByText("1,000.00 USD")).toBeInTheDocument();

    const eurCard = screen.getByRole("link", { name: /EUR/ });
    expect(within(eurCard).getByText("500.00 EUR")).toBeInTheDocument();

    await waitFor(() => {
      expect(analyticsService.getOverview).toHaveBeenCalled();
    });
  });
});
