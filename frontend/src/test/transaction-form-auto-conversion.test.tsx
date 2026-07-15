/**
 * Covers the automatic debounced cross-currency conversion (PR 2 of
 * improve-transfer-transaction-ux): auto-fill, minor-unit rounding, manual
 * override, revert, stale-response guarding, and explicit loading/error states.
 */
import { act, cleanup, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { TransactionForm } from "@/app/dashboard/transactions/transaction-form";
import { exchangeRateService } from "@/services/exchange-rate-service";
import type { Account } from "@/services/account-service";
import type { Category } from "@/services/category-service";
import type { Transaction } from "@/services/transaction-service";
import { renderWithIntl as render } from "@/test/test-utils";

vi.mock("@/services/exchange-rate-service", () => ({
  exchangeRateService: { convert: vi.fn() },
}));

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
const categories: Category[] = [];

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

const convertMock = exchangeRateService.convert as unknown as ReturnType<typeof vi.fn>;

async function openTransferWithAmount(user: ReturnType<typeof userEvent.setup>, amount: string) {
  render(<TransactionForm {...baseProps()} />);
  await user.click(screen.getByRole("radio", { name: "Transfer" }));
  const amountInput = screen.getByLabelText(/Amount \(USD\)/);
  await user.type(amountInput, amount);
  return amountInput;
}

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true });
  convertMock.mockReset();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe("Automatic conversion", () => {
  it("auto-fills the destination amount after the debounce, rounded to VND's 0 fraction digits", async () => {
    convertMock.mockResolvedValue({ from: "USD", to: "VND", amount: 500, convertedAmount: 13095257.887, rate: 26190.515774, asOf: "2026-07-15T00:00:00Z" });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    await openTransferWithAmount(user, "500");

    await act(async () => {
      await vi.advanceTimersByTimeAsync(600);
    });

    const destInput = await screen.findByLabelText(/Destination Amount \(VND\)/) as HTMLInputElement;
    await waitFor(() => expect(destInput.value).toBe("13095258"));
    expect(convertMock).toHaveBeenCalledWith("USD", "VND", "500");
  });

  it("does not require clicking any button — no Auto Convert control exists", async () => {
    convertMock.mockResolvedValue({ from: "USD", to: "VND", amount: 500, convertedAmount: 100, rate: 1, asOf: null });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    await openTransferWithAmount(user, "500");

    expect(screen.queryByRole("button", { name: /convert/i })).not.toBeInTheDocument();
  });

  it("renders no conversion UI for a same-currency transfer", async () => {
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    render(<TransactionForm {...baseProps()} />);
    await user.click(screen.getByRole("radio", { name: "Transfer" }));
    const selects = screen.getAllByRole("combobox");
    await user.selectOptions(selects[1], String(usdSavings.id));

    expect(screen.queryByLabelText(/Destination Amount/)).not.toBeInTheDocument();
    expect(convertMock).not.toHaveBeenCalled();
  });

  it("discards a stale response when a newer request supersedes it", async () => {
    let resolveFirst!: (v: unknown) => void;
    convertMock
      .mockImplementationOnce(() => new Promise((resolve) => { resolveFirst = resolve; }))
      .mockResolvedValueOnce({ from: "USD", to: "VND", amount: 700, convertedAmount: 700000, rate: 1000, asOf: null });

    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    const amountInput = await openTransferWithAmount(user, "500");
    await act(async () => {
      await vi.advanceTimersByTimeAsync(600);
    }); // first request fires and is in flight

    await user.clear(amountInput);
    await user.type(amountInput, "700");
    await act(async () => {
      await vi.advanceTimersByTimeAsync(600);
    }); // second request fires and resolves

    // now let the first (stale) request resolve after the second already won
    await act(async () => {
      resolveFirst({ from: "USD", to: "VND", amount: 500, convertedAmount: 999999, rate: 2000, asOf: null });
      await vi.runOnlyPendingTimersAsync();
    });

    const destInput = await screen.findByLabelText(/Destination Amount \(VND\)/) as HTMLInputElement;
    await waitFor(() => expect(destInput.value).toBe("700000"));
  });

  it("shows a loading indicator while the rate is in flight and an inline error on failure, without blocking submit", async () => {
    convertMock.mockRejectedValue(new Error("network down"));
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    await openTransferWithAmount(user, "500");

    await act(async () => {
      await vi.advanceTimersByTimeAsync(600);
    });

    expect(await screen.findByText("Couldn't fetch a rate — enter the amount manually")).toBeInTheDocument();

    const destInput = screen.getByLabelText(/Destination Amount \(VND\)/) as HTMLInputElement;
    await user.type(destInput, "12000000");
    expect(destInput).not.toBeDisabled();
    expect(screen.getByRole("button", { name: "Save" })).not.toBeDisabled();
  });
});

describe("Manual override", () => {
  it("stops auto-fill once the user manually edits the destination amount", async () => {
    convertMock.mockResolvedValue({ from: "USD", to: "VND", amount: 500, convertedAmount: 13000000, rate: 26000, asOf: null });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    const amountInput = await openTransferWithAmount(user, "500");
    await act(async () => {
      await vi.advanceTimersByTimeAsync(600);
    });

    const destInput = await screen.findByLabelText(/Destination Amount \(VND\)/) as HTMLInputElement;
    await waitFor(() => expect(destInput.value).toBe("13000000"));

    await user.clear(destInput);
    await user.type(destInput, "12500000");

    await user.clear(amountInput);
    await user.type(amountInput, "600");
    await act(async () => {
      await vi.advanceTimersByTimeAsync(600);
    });

    expect(destInput.value).toBe("12500000");
  });

  it("reverts to the fetched rate and resumes auto-fill", async () => {
    convertMock.mockResolvedValue({ from: "USD", to: "VND", amount: 500, convertedAmount: 13000000, rate: 26000, asOf: null });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    await openTransferWithAmount(user, "500");
    await act(async () => {
      await vi.advanceTimersByTimeAsync(600);
    });

    const destInput = await screen.findByLabelText(/Destination Amount \(VND\)/) as HTMLInputElement;
    await waitFor(() => expect(destInput.value).toBe("13000000"));

    await user.clear(destInput);
    await user.type(destInput, "1");
    expect(screen.getByRole("button", { name: "Use fetched rate" })).toBeInTheDocument();

    convertMock.mockResolvedValue({ from: "USD", to: "VND", amount: 500, convertedAmount: 13500000, rate: 27000, asOf: null });
    await user.click(screen.getByRole("button", { name: "Use fetched rate" }));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(600);
    });

    await waitFor(() => expect(destInput.value).toBe("13500000"));
  });
});

describe("Editing an existing cross-currency transfer", () => {
  it("keeps the stored destination amount instead of replacing it with a freshly fetched rate", async () => {
    const editingTx: Transaction = {
      id: 9,
      accountId: usdChecking.id,
      accountName: usdChecking.name,
      currency: "USD",
      transactionType: "TRANSFER",
      amount: "500",
      destinationAmount: "13095258",
      destinationCurrency: "VND",
      transactionDate: "2026-05-01",
      transferAccountId: vndWallet.id,
      transferAccountName: vndWallet.name,
    };
    render(<TransactionForm {...baseProps()} editingTx={editingTx} />);

    const destInput = await screen.findByLabelText(/Destination Amount \(VND\)/) as HTMLInputElement;
    expect(destInput.value).toBe("13095258");

    await act(async () => {
      await vi.advanceTimersByTimeAsync(600);
    });
    expect(convertMock).not.toHaveBeenCalled();
    expect(destInput.value).toBe("13095258");
  });
});
