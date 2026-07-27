import { cleanup, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ReceiptTab } from "@/components/vault/receipt-tab";
import { accountService } from "@/services/account-service";
import { agentRunService } from "@/services/agent-run-service";
import { vaultService, type PageResponse, type VaultDocument } from "@/services/vault-service";
import { renderWithIntl as render } from "@/test/test-utils";

vi.mock("@/services/vault-service");
vi.mock("@/services/account-service");
vi.mock("@/services/agent-run-service", async () => {
  const actual = await vi.importActual<typeof import("@/services/agent-run-service")>(
    "@/services/agent-run-service"
  );
  return { ...actual, agentRunService: { list: vi.fn(), start: vi.fn() } };
});

const account = {
  id: 10,
  name: "Checking",
  accountType: "BANK" as const,
  currency: "USD",
  initialBalance: 0,
  currentBalance: "0.00",
  createdAt: "2026-01-01T00:00:00Z",
};

const receipt: VaultDocument = {
  id: "receipt-1",
  type: "RECEIPT",
  status: "ACTIVE",
  source: "manual",
  capturedAt: "2026-07-24T04:30:43Z",
  hasBinary: true,
  originalFilename: "receipt.pdf",
  accountId: account.id,
};

function page(content: VaultDocument[]): PageResponse<VaultDocument> {
  return { content, totalElements: content.length, totalPages: 1, size: 100, number: 0 };
}

describe("ReceiptTab", () => {
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it("shows receipts across all accounts with the owning account", async () => {
    vi.mocked(accountService.list).mockResolvedValue([account]);
    vi.mocked(agentRunService.list).mockResolvedValue([]);
    vi.mocked(vaultService.listByType).mockResolvedValue(page([receipt]));

    render(<ReceiptTab />);

    expect(await screen.findByText("receipt.pdf")).toBeInTheDocument();
    expect(screen.getAllByText("Checking").length).toBeGreaterThan(0);
    expect(vaultService.listByType).toHaveBeenCalledWith("RECEIPT", undefined, 0, 100);
  });

  it("requests the selected account when the overall filter changes", async () => {
    vi.mocked(accountService.list).mockResolvedValue([account]);
    vi.mocked(agentRunService.list).mockResolvedValue([]);
    vi.mocked(vaultService.listByType).mockResolvedValue(page([receipt]));
    const user = userEvent.setup();

    render(<ReceiptTab />);
    const filter = await screen.findByLabelText("Show files for");
    await waitFor(() => expect(screen.getAllByRole("option", { name: "Checking" })).not.toHaveLength(0));
    await user.selectOptions(filter, String(account.id));

    expect(vaultService.listByType).toHaveBeenCalledWith("RECEIPT", account.id, 0, 100);
  });
});
