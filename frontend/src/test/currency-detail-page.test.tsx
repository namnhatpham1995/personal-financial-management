import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { describe, expect, it, vi, beforeEach } from "vitest";
import CurrencyDetailPage from "@/app/dashboard/currency/[code]/page";
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
  currentBalance: "150.00",
  createdAt: "2026-01-01T00:00:00Z",
};

function renderPage(code: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <CurrencyDetailPage params={{ code }} />
    </QueryClientProvider>
  );
}

describe("CurrencyDetailPage", () => {
  beforeEach(() => {
    vi.mocked(accountService.list).mockResolvedValue([usdAccount]);
    vi.mocked(analyticsService.balances).mockResolvedValue([
      { currency: "USD", totalBalance: "150.00", accounts: [] },
    ]);
    vi.mocked(analyticsService.incomeVsExpense).mockResolvedValue([]);
    vi.mocked(analyticsService.spendingByCategory).mockResolvedValue([]);
    vi.mocked(analyticsService.budgetProgress).mockResolvedValue([]);
    vi.mocked(transactionService.list).mockResolvedValue({
      content: [],
      totalElements: 0,
      totalPages: 1,
      number: 0,
      size: 5,
    });
    vi.mocked(budgetService.list).mockResolvedValue([]);
    vi.mocked(categoryService.list).mockResolvedValue([]);
  });

  it("renders the currency's detail content on a direct deep link", async () => {
    renderPage("USD");

    expect(await screen.findByRole("heading", { name: "USD" })).toBeInTheDocument();
    expect(screen.getByText("Checking")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Back to Overview/i })).toHaveAttribute(
      "href",
      "/dashboard"
    );
  });

  it("normalizes a lowercase currency code in the URL", async () => {
    renderPage("usd");

    expect(await screen.findByRole("heading", { name: "USD" })).toBeInTheDocument();
    await waitFor(() => {
      expect(analyticsService.incomeVsExpense).toHaveBeenCalled();
    });
  });

  it("re-fetches for the page's own range toggle without touching Overview's queries", async () => {
    const user = userEvent.setup();
    renderPage("USD");

    await screen.findByRole("heading", { name: "USD" });
    vi.mocked(analyticsService.incomeVsExpense).mockClear();

    await user.click(screen.getByRole("button", { name: "1M" }));

    await waitFor(() => {
      expect(analyticsService.incomeVsExpense).toHaveBeenCalled();
    });
  });

  it("shows a designed empty state for a currency with no footprint", async () => {
    vi.mocked(analyticsService.balances).mockResolvedValue([]);
    renderPage("CHF");

    expect(await screen.findByText("No accounts or activity in CHF")).toBeInTheDocument();
    expect(
      screen.getByText("You don't hold any balances, transactions, or budgets in this currency.")
    ).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Back to Overview/i })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "CHF" })).not.toBeInTheDocument();
  });
});
