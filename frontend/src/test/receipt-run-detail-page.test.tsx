import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { describe, expect, it, vi, beforeEach } from "vitest";
import ReceiptRunDetailPage from "@/app/dashboard/receipts/[id]/page";
import { agentRunService, type AgentRunDetail } from "@/services/agent-run-service";
import { categoryService } from "@/services/category-service";
import { accountService } from "@/services/account-service";
import { renderWithIntl as render } from "@/test/test-utils";

vi.mock("@/services/agent-run-service", async () => {
  const actual = await vi.importActual<typeof import("@/services/agent-run-service")>(
    "@/services/agent-run-service"
  );
  return { ...actual, agentRunService: { getById: vi.fn(), decide: vi.fn() } };
});
vi.mock("@/services/category-service");
vi.mock("@/services/account-service");

function renderPage(id = "42") {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <ReceiptRunDetailPage params={{ id }} />
    </QueryClientProvider>
  );
}

const baseRun: AgentRunDetail = {
  id: 42,
  vaultDocumentId: "doc-42",
  status: "AWAITING_REVIEW",
  extraction: { merchant: "Corner Market", date: "2026-01-05", currency: "USD", lineItems: [], total: "12.50" },
  proposals: [
    {
      merchant: "Corner Market",
      date: "2026-01-05",
      amount: "12.50",
      currency: "USD",
      categoryId: null,
      accountId: 10,
      description: "Milk",
      flags: ["low-confidence"],
      excluded: false,
    },
  ],
  failureReason: null,
  retryable: false,
  createdTransactionIds: null,
  createdAt: "2026-01-05T00:00:00Z",
  updatedAt: "2026-01-05T00:00:00Z",
};

describe("ReceiptRunDetailPage", () => {
  beforeEach(() => {
    vi.mocked(categoryService.list).mockResolvedValue([
      { id: 1, name: "Groceries", transactionType: "EXPENSE", system: false },
    ]);
    vi.mocked(accountService.list).mockResolvedValue([
      { id: 10, name: "Checking", accountType: "BANK", currency: "USD", initialBalance: 0, currentBalance: "0", createdAt: "2026-01-01T00:00:00Z" },
    ]);
  });

  it("visibly flags a low-confidence proposal", async () => {
    vi.mocked(agentRunService.getById).mockResolvedValue(baseRun);
    renderPage();

    expect(await screen.findByText("Low confidence — pick a category")).toBeInTheDocument();
  });

  it("disables approve until every included proposal has a category", async () => {
    vi.mocked(agentRunService.getById).mockResolvedValue(baseRun);
    renderPage();

    const approveButton = await screen.findByRole("button", { name: "Approve" });
    expect(approveButton).toBeDisabled();
  });

  it("submits the edited category, not the original null value", async () => {
    vi.mocked(agentRunService.getById).mockResolvedValue(baseRun);
    vi.mocked(agentRunService.decide).mockResolvedValue({ ...baseRun, status: "COMMITTED", createdTransactionIds: [501] });
    const user = userEvent.setup();
    renderPage();

    const categorySelect = await screen.findByDisplayValue("Select category…");
    await user.selectOptions(categorySelect, "1");

    const approveButton = screen.getByRole("button", { name: "Approve" });
    expect(approveButton).not.toBeDisabled();
    await user.click(approveButton);

    await waitFor(() => {
      expect(agentRunService.decide).toHaveBeenCalledWith(
        42,
        expect.objectContaining({
          approve: true,
          proposals: [expect.objectContaining({ categoryId: 1, accountId: 10 })],
        })
      );
    });
  });

  it("excluding the only item still requires no category on it, but blocks approval with zero included items", async () => {
    vi.mocked(agentRunService.getById).mockResolvedValue(baseRun);
    const user = userEvent.setup();
    renderPage();

    await screen.findByRole("button", { name: "Approve" });
    await user.click(screen.getByRole("button", { name: "Exclude this item" }));

    expect(screen.getByRole("button", { name: "Approve" })).toBeDisabled();
  });

  it("disables actions while a decision is in flight", async () => {
    // getById is refetched on invalidation after a successful decision (the query key
    // ["agent-runs", runId] is a prefix match of the invalidated ["agent-runs"]), so the mock
    // must reflect the post-decision state once the mutation resolves, like a real backend would.
    const committed: AgentRunDetail = { ...baseRun, status: "COMMITTED", createdTransactionIds: [501] };
    vi.mocked(agentRunService.getById).mockResolvedValue(baseRun);
    let resolveDecide: (v: AgentRunDetail) => void = () => {};
    vi.mocked(agentRunService.decide).mockReturnValue(
      new Promise((resolve) => {
        resolveDecide = resolve;
      })
    );
    const user = userEvent.setup();
    renderPage();

    const categorySelect = await screen.findByDisplayValue("Select category…");
    await user.selectOptions(categorySelect, "1");
    await user.click(screen.getByRole("button", { name: "Approve" }));

    expect(screen.getByRole("button", { name: "Approving…" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Reject" })).toBeDisabled();

    vi.mocked(agentRunService.getById).mockResolvedValue(committed);
    resolveDecide(committed);
    await waitFor(() => {
      expect(screen.getByText("1 transaction created")).toBeInTheDocument();
    });
  });

  it("surfaces an error and lets the user retry after a failed decision", async () => {
    const committed: AgentRunDetail = { ...baseRun, status: "COMMITTED", createdTransactionIds: [501] };
    vi.mocked(agentRunService.getById).mockResolvedValue(baseRun);
    vi.mocked(agentRunService.decide)
      .mockRejectedValueOnce(new Error("network error"))
      .mockImplementationOnce(async () => {
        vi.mocked(agentRunService.getById).mockResolvedValue(committed);
        return committed;
      });
    const user = userEvent.setup();
    renderPage();

    const categorySelect = await screen.findByDisplayValue("Select category…");
    await user.selectOptions(categorySelect, "1");
    await user.click(screen.getByRole("button", { name: "Approve" }));

    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Approve" })).not.toBeDisabled();
    });
    // The run stayed AWAITING_REVIEW after the failed attempt — still reviewable, per spec.
    expect(screen.getByRole("button", { name: "Approve" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Approve" }));
    await waitFor(() => {
      expect(screen.getByText("1 transaction created")).toBeInTheDocument();
    });
  });
});
