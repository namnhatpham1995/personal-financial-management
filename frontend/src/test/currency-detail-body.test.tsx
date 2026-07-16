import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { InternalAxiosRequestConfig } from "axios";
import type { ComponentProps } from "react";
import { describe, expect, it, vi, beforeEach } from "vitest";
import { CurrencyDetailBody } from "@/components/overview/currency-detail-body";
import { accountService, type Account } from "@/services/account-service";
import { transactionService } from "@/services/transaction-service";
import { analyticsService } from "@/services/analytics-service";
import { budgetService } from "@/services/budget-service";
import { categoryService } from "@/services/category-service";
import { renderWithIntl as render } from "@/test/test-utils";

vi.mock("@/services/account-service", async () => {
  const actual = await vi.importActual<typeof import("@/services/account-service")>(
    "@/services/account-service"
  );
  return {
    ...actual,
    accountService: {
      ...actual.accountService,
      create: vi.fn(),
      update: vi.fn(),
      delete: vi.fn(),
      deletePreview: vi.fn(),
    },
  };
});
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

function renderBody(overrides: Partial<ComponentProps<typeof CurrencyDetailBody>> = {}) {
  const props: ComponentProps<typeof CurrencyDetailBody> = {
    currency: "USD",
    nativeTotal: "150.00",
    trend: [],
    spending: [],
    accounts: [usdAccount],
    ...overrides,
  };
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <CurrencyDetailBody {...props} />
    </QueryClientProvider>
  );
  return props;
}

describe("CurrencyDetailBody", () => {
  beforeEach(() => {
    vi.mocked(transactionService.list).mockResolvedValue({
      content: [],
      totalElements: 0,
      totalPages: 1,
      number: 0,
      size: 5,
    });
    vi.mocked(analyticsService.budgetProgress).mockResolvedValue([]);
    vi.mocked(budgetService.list).mockResolvedValue([]);
    vi.mocked(categoryService.list).mockResolvedValue([]);
    vi.mocked(accountService.create).mockReset();
  });

  it("renders the currency section with accounts", () => {
    renderBody();
    expect(screen.getByRole("heading", { name: "USD" })).toBeInTheDocument();
    expect(screen.getByText("Checking")).toBeInTheDocument();
  });

  it("links the currency heading when headerHref is provided", () => {
    renderBody({ headerHref: "/dashboard/currency/USD" });
    expect(screen.getByRole("link", { name: "USD" })).toHaveAttribute(
      "href",
      "/dashboard/currency/USD"
    );
  });

  it("does not link the currency heading when headerHref is omitted", () => {
    renderBody();
    expect(screen.queryByRole("link", { name: "USD" })).not.toBeInTheDocument();
  });

  it("opens the add-account form pre-filled with this currency and submits it", async () => {
    const user = userEvent.setup();
    vi.mocked(accountService.create).mockResolvedValue(usdAccount);
    renderBody({ currency: "EUR", accounts: [] });

    await user.click(screen.getByRole("button", { name: "Add account" }));

    const form = screen.getByRole("heading", { name: "Add account" }).closest("div");
    expect(form).not.toBeNull();
    expect(within(form as HTMLElement).getByLabelText("Currency (ISO code)")).toHaveValue("EUR");

    await user.type(within(form as HTMLElement).getByLabelText("Name"), "Brokerage");
    await user.click(within(form as HTMLElement).getByRole("button", { name: "Save" }));

    await waitFor(() => {
      expect(accountService.create).toHaveBeenCalledWith(
        expect.objectContaining({ name: "Brokerage", currency: "EUR" })
      );
    });
  });

  it("shows an empty-accounts state with an add-account action when this currency has no accounts", () => {
    renderBody({ accounts: [], nativeTotal: undefined });
    expect(screen.getByText("No accounts in this currency yet.")).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: "Add account" }).length).toBeGreaterThan(0);
  });

  it("edits an account via the account box's edit action", async () => {
    const user = userEvent.setup();
    vi.mocked(accountService.update).mockResolvedValue(usdAccount);
    renderBody();

    await user.click(screen.getByRole("button", { name: "Edit Checking" }));
    expect(screen.getByRole("heading", { name: "Edit account" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Save changes" }));

    await waitFor(() => {
      expect(accountService.update).toHaveBeenCalledWith(usdAccount.id, expect.any(Object));
    });
  });

  it("deletes an account via the account box's delete action", async () => {
    const user = userEvent.setup();
    vi.mocked(accountService.deletePreview).mockResolvedValue({
      accountId: usdAccount.id,
      transactionCount: 0,
    });
    vi.mocked(accountService.delete).mockResolvedValue({
      data: undefined,
      status: 204,
      statusText: "No Content",
      headers: {},
      config: {} as InternalAxiosRequestConfig,
    });
    renderBody();

    await user.click(screen.getByRole("button", { name: "Delete Checking" }));
    expect(await screen.findByText("This account has no transactions.")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Delete account" }));

    await waitFor(() => {
      expect(accountService.delete).toHaveBeenCalledWith(usdAccount.id);
    });
  });
});
