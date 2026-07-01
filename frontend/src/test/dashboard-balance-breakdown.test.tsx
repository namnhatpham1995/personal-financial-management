import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ComponentProps } from "react";
import { describe, expect, it, vi } from "vitest";
import { BalanceBreakdown } from "@/components/accounts/balance-breakdown";
import type { Account } from "@/services/account-service";
import type { CurrencyNetWorth } from "@/services/analytics-service";

vi.mock("@/components/charts/cash-flow-chart", () => ({
  CashFlowChart: () => null,
}));

vi.mock("@/components/charts/spending-donut-chart", () => ({
  SpendingDonutChart: () => null,
}));

vi.mock("@/components/charts/budget-progress-manager", () => ({
  BudgetProgressManager: () => null,
}));

vi.mock("@/components/charts/rates-used-note", () => ({
  RatesUsedNote: () => null,
}));

const accounts: Account[] = [
  {
    id: 1,
    name: "Main Checking",
    accountType: "BANK",
    currency: "USD",
    initialBalance: "1000",
    currentBalance: "1250.50",
    createdAt: "2026-01-01T00:00:00Z",
  },
  {
    id: 2,
    name: "Rewards Card",
    accountType: "CREDIT_CARD",
    currency: "USD",
    initialBalance: "0",
    currentBalance: "240.25",
    createdAt: "2026-01-01T00:00:00Z",
  },
  {
    id: 3,
    name: "Euro Savings",
    accountType: "SAVINGS",
    currency: "EUR",
    initialBalance: "500",
    currentBalance: "500",
    createdAt: "2026-01-01T00:00:00Z",
  },
];

const netWorthByCurrency: CurrencyNetWorth[] = [
  {
    currency: "USD",
    totalAssets: "1250.50",
    totalLiabilities: "240.25",
    netWorth: "1010.25",
    accounts: [],
  },
  {
    currency: "EUR",
    totalAssets: "500",
    totalLiabilities: "0",
    netWorth: "500",
    accounts: [],
  },
];

function renderBreakdown(overrides: Partial<ComponentProps<typeof BalanceBreakdown>> = {}) {
  const props: ComponentProps<typeof BalanceBreakdown> = {
    accounts,
    netWorthByCurrency,
    convertedCurrency: null,
    showCreateForm: false,
    isCreating: false,
    onAdd: vi.fn(),
    onCreate: vi.fn(),
    onCancelCreate: vi.fn(),
    onEdit: vi.fn(),
    onDelete: vi.fn(),
    onOpenDetail: vi.fn(),
    ...overrides,
  };

  render(<BalanceBreakdown {...props} />);
  return props;
}

describe("BalanceBreakdown", () => {
  it("renders account rows as grouped asset and liability contributors", () => {
    renderBreakdown();

    expect(screen.getByText("Balance Breakdown")).toBeInTheDocument();
    expect(screen.getByText("Main Checking")).toBeInTheDocument();
    expect(screen.getByText("Rewards Card")).toBeInTheDocument();
    expect(screen.getByText("Euro Savings")).toBeInTheDocument();
    expect(screen.getAllByText("USD").length).toBeGreaterThan(0);
    expect(screen.getAllByText("EUR").length).toBeGreaterThan(0);
    expect(screen.getAllByText("Asset").length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText("Liability")).toBeInTheDocument();
  });

  it("shows an empty state with an add-account action", async () => {
    const user = userEvent.setup();
    const onAdd = vi.fn();
    renderBreakdown({
      accounts: [],
      netWorthByCurrency: [],
      onAdd,
    });

    expect(screen.getByText("No accounts yet")).toBeInTheDocument();
    expect(
      screen.getByText("Add a cash, bank, savings, or credit account to start tracking balances.")
    ).toBeInTheDocument();

    await user.click(screen.getAllByRole("button", { name: "Add account" })[0]);
    expect(onAdd).toHaveBeenCalledTimes(1);
  });

  it("labels native account rows when totals are converted", () => {
    renderBreakdown({ convertedCurrency: "USD" });

    expect(
      screen.getByText("Account rows show native balances; converted totals above are shown in USD.")
    ).toBeInTheDocument();
  });

  it("calls edit and delete actions from account rows", async () => {
    const user = userEvent.setup();
    const onEdit = vi.fn();
    const onDelete = vi.fn();
    renderBreakdown({ onEdit, onDelete });

    await user.click(screen.getByRole("button", { name: "Edit Rewards Card" }));
    expect(onEdit).toHaveBeenCalledWith(accounts[1]);

    await user.click(screen.getByRole("button", { name: "Delete Rewards Card" }));
    expect(onDelete).toHaveBeenCalledWith(accounts[1]);
  });

  it("opens the account detail view when an account box is activated", async () => {
    const user = userEvent.setup();
    const onOpenDetail = vi.fn();
    const onEdit = vi.fn();
    renderBreakdown({ onOpenDetail, onEdit });

    // Activating the box opens the detail view.
    await user.click(screen.getByRole("button", { name: /View Main Checking details/i }));
    expect(onOpenDetail).toHaveBeenCalledWith(accounts[0]);

    // Acting on the inner edit button must NOT also open the detail view.
    onOpenDetail.mockClear();
    await user.click(screen.getByRole("button", { name: "Edit Rewards Card" }));
    expect(onEdit).toHaveBeenCalledWith(accounts[1]);
    expect(onOpenDetail).not.toHaveBeenCalled();
  });

  it("submits a new account from the inline Overview form", async () => {
    const user = userEvent.setup();
    const onCreate = vi.fn();
    renderBreakdown({ showCreateForm: true, onCreate });

    const form = screen.getByRole("heading", { name: "Add account" }).closest("div");
    expect(form).not.toBeNull();

    await user.type(within(form as HTMLElement).getByLabelText("Name"), "Brokerage");
    await user.clear(within(form as HTMLElement).getByLabelText("Initial balance"));
    await user.type(within(form as HTMLElement).getByLabelText("Initial balance"), "42");
    await user.click(within(form as HTMLElement).getByRole("button", { name: "Save" }));

    await waitFor(() => {
      expect(onCreate).toHaveBeenCalled();
      expect(onCreate.mock.calls[0][0]).toEqual(
        expect.objectContaining({
          name: "Brokerage",
          currency: "USD",
          initialBalance: "42",
        })
      );
    });
  });
});
