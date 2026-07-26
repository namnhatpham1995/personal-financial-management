import { cleanup, fireEvent, screen, waitFor } from "@testing-library/react";
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
  accountType: "BANK",
  currency: "USD",
  initialBalance: 0,
  currentBalance: "0.00",
  createdAt: "2026-01-01T00:00:00Z",
};

const legacyReceipt: VaultDocument = {
  id: "legacy-receipt",
  type: "RECEIPT",
  status: "ACTIVE",
  source: "manual",
  capturedAt: "2026-07-24T04:30:43Z",
  hasBinary: true,
  originalFilename: "nam-nhat-pham-cv.pdf",
};

function page(content: VaultDocument[]): PageResponse<VaultDocument> {
  return { content, totalElements: content.length, totalPages: 1, size: 100, number: 0 };
}

describe("ReceiptTab legacy receipt recovery", () => {
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it("surfaces an account-less legacy receipt before an account is selected", async () => {
    vi.mocked(accountService.list).mockResolvedValue([account]);
    vi.mocked(agentRunService.list).mockResolvedValue([]);
    vi.mocked(vaultService.listUnassignedReceipts).mockResolvedValue(page([legacyReceipt]));

    render(<ReceiptTab />);

    expect(await screen.findByText("nam-nhat-pham-cv.pdf")).toBeInTheDocument();
    expect(screen.getByText("Unassigned receipts")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Start ingestion" })).not.toBeInTheDocument();
  });

  it("assigns a legacy receipt to an owned account and removes it from recovery", async () => {
    vi.mocked(accountService.list).mockResolvedValue([account]);
    vi.mocked(agentRunService.list).mockResolvedValue([]);
    vi.mocked(vaultService.listUnassignedReceipts)
      .mockResolvedValueOnce(page([legacyReceipt]))
      .mockResolvedValue(page([]));
    vi.mocked(vaultService.assignAccount).mockResolvedValue({ ...legacyReceipt, accountId: account.id });

    render(<ReceiptTab />);

    const accountSelect = await screen.findByRole("combobox", {
      name: "Account for nam-nhat-pham-cv.pdf",
    });
    fireEvent.change(accountSelect, { target: { value: String(account.id) } });
    fireEvent.click(screen.getByRole("button", { name: "Assign" }));

    await waitFor(() => {
      expect(vaultService.assignAccount).toHaveBeenCalledWith("legacy-receipt", account.id);
    });
    await waitFor(() => {
      expect(screen.queryByText("nam-nhat-pham-cv.pdf")).not.toBeInTheDocument();
    });
  });
});
