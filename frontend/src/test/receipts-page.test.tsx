import { screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { describe, expect, it, vi } from "vitest";
import ReceiptsPage from "@/app/dashboard/receipts/page";
import { agentRunService, type AgentRunSummary } from "@/services/agent-run-service";
import { renderWithIntl as render } from "@/test/test-utils";

vi.mock("@/services/agent-run-service", async () => {
  const actual = await vi.importActual<typeof import("@/services/agent-run-service")>(
    "@/services/agent-run-service"
  );
  return { ...actual, agentRunService: { list: vi.fn() } };
});

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <ReceiptsPage />
    </QueryClientProvider>
  );
}

const runs: AgentRunSummary[] = [
  { id: 1, vaultDocumentId: "doc-1", status: "COMMITTED", createdAt: "2026-01-01T00:00:00Z", updatedAt: "2026-01-01T00:00:00Z" },
  { id: 2, vaultDocumentId: "doc-2", status: "AWAITING_REVIEW", createdAt: "2026-01-02T00:00:00Z", updatedAt: "2026-01-02T00:00:00Z" },
  { id: 3, vaultDocumentId: "doc-3", status: "FAILED", createdAt: "2026-01-03T00:00:00Z", updatedAt: "2026-01-03T00:00:00Z" },
];

describe("ReceiptsPage", () => {
  it("shows an empty state when there are no runs", async () => {
    vi.mocked(agentRunService.list).mockResolvedValue([]);
    renderPage();

    expect(await screen.findByText("No ingestion runs yet")).toBeInTheDocument();
  });

  it("prioritizes runs awaiting review before terminal runs", async () => {
    vi.mocked(agentRunService.list).mockResolvedValue(runs);
    renderPage();

    const rows = await screen.findAllByRole("row");
    // rows[0] is the header row
    expect(rows[1]).toHaveTextContent("Awaiting review");
  });

  it("shows an unavailable state on a 503 from the agent endpoint", async () => {
    vi.mocked(agentRunService.list).mockRejectedValue({
      response: { status: 503 },
    });
    renderPage();

    expect(await screen.findByText("Receipt ingestion isn't available")).toBeInTheDocument();
  });

  it("shows a generic load error for a non-503 failure", async () => {
    vi.mocked(agentRunService.list).mockRejectedValue({
      response: { status: 500 },
    });
    renderPage();

    expect(await screen.findByText("Could not load this run.")).toBeInTheDocument();
    expect(screen.queryByText("Receipt ingestion isn't available")).not.toBeInTheDocument();
  });
});
