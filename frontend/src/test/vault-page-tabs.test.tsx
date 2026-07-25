/**
 * Task 4.1/4.8: the Browse tab is removed; Statement and Receipt are the only top-level tabs.
 */
import { cleanup, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { describe, expect, it, vi, afterEach } from "vitest";
import VaultPage from "@/app/dashboard/vault/page";
import { accountService } from "@/services/account-service";
import { agentRunService } from "@/services/agent-run-service";
import { renderWithIntl as render } from "@/test/test-utils";

vi.mock("@/services/vault-service");
vi.mock("@/services/account-service");
vi.mock("@/services/agent-run-service", async () => {
  const actual = await vi.importActual<typeof import("@/services/agent-run-service")>(
    "@/services/agent-run-service"
  );
  return { ...actual, agentRunService: { list: vi.fn(), start: vi.fn() } };
});

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <VaultPage />
    </QueryClientProvider>
  );
}

describe("VaultPage tabs", () => {
  afterEach(() => cleanup());

  it("renders Statement and Receipt tabs, with no Browse tab", async () => {
    vi.mocked(accountService.list).mockResolvedValue([]);
    vi.mocked(agentRunService.list).mockResolvedValue([]);
    renderPage();

    expect(await screen.findByRole("button", { name: "Statement" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Receipt" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Browse" })).not.toBeInTheDocument();
  });
});
