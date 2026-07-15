/**
 * Covers the transfer-entry UX restructure (PR 1 of improve-transfer-transaction-ux):
 * segmented type control, directional From/To layout, same-account exclusion, and
 * today-default date. Auto-conversion behavior itself is covered separately once the
 * automatic hook lands.
 */
import { cleanup, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { TransactionForm } from "@/app/dashboard/transactions/transaction-form";
import type { Account } from "@/services/account-service";
import type { Category } from "@/services/category-service";
import type { Transaction } from "@/services/transaction-service";
import { renderWithIntl as render } from "@/test/test-utils";

const usdChecking: Account = {
  id: 1,
  name: "USD Checking",
  accountType: "CHECKING",
  currency: "USD",
  initialBalance: 0,
  currentBalance: "1000",
  createdAt: "2026-01-01T00:00:00Z",
};
const vndWallet: Account = {
  id: 2,
  name: "VND Wallet",
  accountType: "CHECKING",
  currency: "VND",
  initialBalance: 0,
  currentBalance: "0",
  createdAt: "2026-01-01T00:00:00Z",
};
const usdSavings: Account = {
  id: 3,
  name: "USD Savings",
  accountType: "SAVINGS",
  currency: "USD",
  initialBalance: 0,
  currentBalance: "0",
  createdAt: "2026-01-01T00:00:00Z",
};
const accounts = [usdChecking, vndWallet, usdSavings];

const categories: Category[] = [
  { id: 10, name: "Salary", transactionType: "INCOME", system: false },
  { id: 11, name: "Groceries", transactionType: "EXPENSE", system: false },
];

function baseProps() {
  return {
    editingTx: null as Transaction | null,
    accounts,
    categories,
    isPending: false,
    onCancel: vi.fn(),
    onSubmit: vi.fn(),
  };
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("Transaction type segmented control", () => {
  it("shows all three types as one-click options and defaults to Income", () => {
    render(<TransactionForm {...baseProps()} />);

    expect(screen.getByRole("radio", { name: "Income" })).toHaveAttribute("aria-checked", "true");
    expect(screen.getByRole("radio", { name: "Expense" })).toBeInTheDocument();
    expect(screen.getByRole("radio", { name: "Transfer" })).toBeInTheDocument();
  });

  it("switches to Transfer mode and re-filters categories in one click", async () => {
    const user = userEvent.setup();
    render(<TransactionForm {...baseProps()} />);

    await user.click(screen.getByRole("radio", { name: "Expense" }));
    expect(screen.getByRole("radio", { name: "Expense" })).toHaveAttribute("aria-checked", "true");
    expect(screen.getByRole("option", { name: "Groceries" })).toBeInTheDocument();

    await user.click(screen.getByRole("radio", { name: "Transfer" }));
    expect(screen.getByRole("radio", { name: "Transfer" })).toHaveAttribute("aria-checked", "true");
    expect(screen.getByText("From")).toBeInTheDocument();
    expect(screen.getByText("To")).toBeInTheDocument();
  });

  it("disables the control while editing an existing transaction", () => {
    const editingTx: Transaction = {
      id: 5,
      accountId: usdChecking.id,
      accountName: usdChecking.name,
      currency: "USD",
      transactionType: "EXPENSE",
      amount: "20",
      transactionDate: "2026-01-05",
      categoryId: 11,
    };
    render(<TransactionForm {...baseProps()} editingTx={editingTx} />);

    expect(screen.getByRole("radio", { name: "Expense" })).toBeDisabled();
  });
});

describe("Directional transfer entry", () => {
  it("excludes the selected source account from the destination options", async () => {
    const user = userEvent.setup();
    render(<TransactionForm {...baseProps()} />);
    await user.click(screen.getByRole("radio", { name: "Transfer" }));

    const [fromSelect, toSelect] = screen.getAllByRole("combobox").filter((el) =>
      ["USD Checking", "VND Wallet", "USD Savings"].includes((el as HTMLSelectElement).options[0]?.textContent ?? "")
    );
    // default source is account 1 (USD Checking); it must not appear in the destination list
    const toOptionLabels = Array.from((toSelect as HTMLSelectElement).options).map((o) => o.textContent);
    expect(toOptionLabels).not.toContain("USD Checking");
    expect(toOptionLabels).toEqual(expect.arrayContaining(["VND Wallet", "USD Savings"]));
    expect(fromSelect).toBeTruthy();
  });

  it("re-picks a valid destination when the source account changes to match it, instead of leaving a stale match", async () => {
    const user = userEvent.setup();
    render(<TransactionForm {...baseProps()} />);
    await user.click(screen.getByRole("radio", { name: "Transfer" }));

    const selects = screen.getAllByRole("combobox");
    const fromSelect = selects[0] as HTMLSelectElement;
    const toSelect = selects[1] as HTMLSelectElement;

    await user.selectOptions(toSelect, String(usdSavings.id));
    expect(toSelect.value).toBe(String(usdSavings.id));

    await user.selectOptions(fromSelect, String(usdSavings.id));

    // The stale match is cleared, and — since a blank native <select> would just silently
    // default back to its first option without form state ever learning about it — a fresh
    // valid destination (never the new source) is auto-selected instead of leaving it unset.
    await waitFor(() => expect(toSelect.value).not.toBe(String(usdSavings.id)));
    expect(toSelect.value).not.toBe("");
  });
});

describe("Date default", () => {
  it("pre-fills today's date for a new transaction", () => {
    render(<TransactionForm {...baseProps()} />);
    const dateInput = screen.getByLabelText("Date") as HTMLInputElement;
    const today = new Date();
    const expected = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, "0")}-${String(today.getDate()).padStart(2, "0")}`;
    expect(dateInput.value).toBe(expected);
  });

  it("keeps the stored date when editing an existing transaction", () => {
    const editingTx: Transaction = {
      id: 5,
      accountId: usdChecking.id,
      accountName: usdChecking.name,
      currency: "USD",
      transactionType: "EXPENSE",
      amount: "20",
      transactionDate: "2025-03-14",
      categoryId: 11,
    };
    render(<TransactionForm {...baseProps()} editingTx={editingTx} />);
    const dateInput = screen.getByLabelText("Date") as HTMLInputElement;
    expect(dateInput.value).toBe("2025-03-14");
  });
});

describe("All three type flows still submit", () => {
  it("submits an income transaction", async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn();
    render(<TransactionForm {...baseProps()} onSubmit={onSubmit} />);

    await user.type(screen.getByLabelText("Amount"), "100");
    await user.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() => expect(onSubmit).toHaveBeenCalled());
    expect(onSubmit.mock.calls[0][0]).toMatchObject({ transactionType: "INCOME", amount: "100" });
  });

  it("submits a same-currency transfer without a destination amount", async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn();
    render(<TransactionForm {...baseProps()} onSubmit={onSubmit} />);

    await user.click(screen.getByRole("radio", { name: "Transfer" }));
    const selects = screen.getAllByRole("combobox");
    await user.selectOptions(selects[1], String(usdSavings.id));
    await user.type(screen.getByLabelText(/Amount \(USD\)/), "50");
    await user.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() => expect(onSubmit).toHaveBeenCalled());
    expect(onSubmit.mock.calls[0][0]).toMatchObject({
      transactionType: "TRANSFER",
      accountId: usdChecking.id,
      transferAccountId: usdSavings.id,
      amount: "50",
    });
  });
});
