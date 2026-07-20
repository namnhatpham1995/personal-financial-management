/**
 * Task 7.3: recurring-rule creation must not double-submit on a rapid double-click.
 * The submit button now disables on the mutation's own isPending (not react-hook-form's
 * isSubmitting, which resolves almost instantly for a synchronous submit callback), and
 * the submit handler itself guards against a race between click and re-render.
 */
import { cleanup, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { RecurringTab } from "@/app/dashboard/transactions/recurring-tab";
import { recurringService } from "@/services/recurring-service";
import { accountService } from "@/services/account-service";
import { renderWithIntl as render } from "@/test/test-utils";

vi.mock("@/services/recurring-service");
vi.mock("@/services/account-service");

function renderTab() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <RecurringTab />
    </QueryClientProvider>
  );
}

describe("RecurringTab: double-submit guard", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(recurringService.list).mockResolvedValue([]);
    vi.mocked(accountService.list).mockResolvedValue([
      {
        id: 10,
        name: "Checking",
        accountType: "BANK",
        currency: "USD",
        initialBalance: 0,
        currentBalance: "0",
        createdAt: "2026-01-01T00:00:00Z",
      },
    ]);
  });

  afterEach(() => {
    cleanup();
  });

  it("invokes the create service exactly once on a rapid double-click with a slow mutation", async () => {
    let resolveCreate: (v: Awaited<ReturnType<typeof recurringService.create>>) => void = () => {};
    vi.mocked(recurringService.create).mockReturnValue(
      new Promise((resolve) => {
        resolveCreate = resolve;
      })
    );
    const user = userEvent.setup();
    const { container } = renderTab();

    await user.click(await screen.findByRole("button", { name: "New Rule" }));

    // The recurring form's Field wrapper doesn't associate <label> with its control (no
    // htmlFor/implicit wrap), so these fields aren't getByLabelText-addressable — select
    // by input type instead, matching this form's own DOM shape.
    const amountInput = container.querySelector('input[type="number"][step="0.01"]') as HTMLInputElement;
    const startDateInput = container.querySelector('input[type="date"]') as HTMLInputElement;
    await user.type(amountInput, "50");
    await user.type(startDateInput, "2026-02-01");

    const submitButton = screen.getByRole("button", { name: "Save" });
    // Fire both clicks before the mutation resolves — the second click should be a no-op
    // while the first is still in flight.
    await user.click(submitButton);
    await user.click(submitButton);

    expect(recurringService.create).toHaveBeenCalledTimes(1);
    expect(submitButton).toBeDisabled();

    resolveCreate({
      id: 1,
      accountId: 10,
      accountName: "Checking",
      transactionType: "INCOME",
      amount: "50",
      frequency: "DAILY",
      intervalValue: 1,
      startDate: "2026-02-01",
      nextRunDate: "2026-02-02",
      occurrencesCount: 0,
      active: true,
    });

    await waitFor(() => {
      expect(recurringService.create).toHaveBeenCalledTimes(1);
    });
  });
});
