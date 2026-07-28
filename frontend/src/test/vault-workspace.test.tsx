/**
 * Covers the unified Vault workspace: default attention filtering, mixed-type listing,
 * combined filters, month grouping, and in-place statement review — replacing the removed
 * tab-oriented tests (vault-page-tabs, vault-statement-tab, vault-receipt-tab).
 */
import { cleanup, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { VaultWorkspace } from "@/components/vault/vault-workspace";
import { vaultService, type VaultDocument, type PageResponse } from "@/services/vault-service";
import { accountService, type Account } from "@/services/account-service";
import { agentRunService } from "@/services/agent-run-service";
import { categoryService } from "@/services/category-service";
import { renderWithIntl as render } from "@/test/test-utils";

vi.mock("@/services/vault-service");
vi.mock("@/services/account-service");
vi.mock("@/services/category-service");
vi.mock("@/services/agent-run-service", async () => {
  const actual = await vi.importActual<typeof import("@/services/agent-run-service")>(
    "@/services/agent-run-service"
  );
  return { ...actual, agentRunService: { list: vi.fn(), start: vi.fn() } };
});

const accounts: Account[] = [
  { id: 1, name: "Checking", accountType: "BANK", currency: "USD", initialBalance: 0, currentBalance: "0", createdAt: "2026-01-01T00:00:00Z" },
];

function page(content: VaultDocument[]): PageResponse<VaultDocument> {
  return { content, totalElements: content.length, totalPages: 1, size: 50, number: 0 };
}

const readyStatement: VaultDocument = {
  id: "doc-statement-ready",
  accountId: 1,
  type: "STATEMENT",
  status: "UPLOADED",
  source: "manual",
  capturedAt: "2026-01-05T00:00:00Z",
  hasBinary: true,
  originalFilename: "jan.csv",
};

const unprocessedReceipt: VaultDocument = {
  id: "doc-receipt-unprocessed",
  accountId: 1,
  type: "RECEIPT",
  status: "UPLOADED",
  source: "manual",
  capturedAt: "2026-01-10T00:00:00Z",
  hasBinary: true,
  originalFilename: "lunch.jpg",
};

describe("VaultWorkspace", () => {
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it("defaults to the needs-attention filter and lists both document types together", async () => {
    vi.mocked(accountService.list).mockResolvedValue(accounts);
    vi.mocked(agentRunService.list).mockResolvedValue([]);
    vi.mocked(vaultService.workspace).mockResolvedValue(page([readyStatement, unprocessedReceipt]));
    vi.mocked(vaultService.workspaceStageCounts).mockResolvedValue({ READY_TO_IMPORT: 1, NOT_PROCESSED: 1 });

    render(<VaultWorkspace />);

    expect(await screen.findByText("jan.csv")).toBeInTheDocument();
    expect(screen.getByText("lunch.jpg")).toBeInTheDocument();
    expect(vaultService.workspace).toHaveBeenCalledWith(
      expect.objectContaining({ stages: ["READY_TO_IMPORT", "NEEDS_REVIEW", "FAILED"] })
    );
  });

  it("month-groups documents by capture date", async () => {
    vi.mocked(accountService.list).mockResolvedValue(accounts);
    vi.mocked(agentRunService.list).mockResolvedValue([]);
    vi.mocked(vaultService.workspace).mockResolvedValue(page([readyStatement]));
    vi.mocked(vaultService.workspaceStageCounts).mockResolvedValue({});

    render(<VaultWorkspace />);
    await screen.findByText("jan.csv");
    expect(screen.getByText("January 2026")).toBeInTheDocument();
  });

  it("filtering to one account requests that accountId", async () => {
    vi.mocked(accountService.list).mockResolvedValue(accounts);
    vi.mocked(agentRunService.list).mockResolvedValue([]);
    vi.mocked(vaultService.workspace).mockResolvedValue(page([readyStatement]));
    vi.mocked(vaultService.workspaceStageCounts).mockResolvedValue({});
    const user = userEvent.setup();

    render(<VaultWorkspace />);
    await screen.findByText("jan.csv");
    await waitFor(() => expect(screen.getAllByRole("option", { name: "Checking" })).not.toHaveLength(0));
    await user.selectOptions(screen.getByLabelText("Show files for"), "Checking");

    await waitFor(() =>
      expect(vaultService.workspace).toHaveBeenCalledWith(expect.objectContaining({ accountId: 1 }))
    );
  });

  it("reviewing a ready-to-import statement opens the wizard in place, replacing the list", async () => {
    vi.mocked(accountService.list).mockResolvedValue(accounts);
    vi.mocked(agentRunService.list).mockResolvedValue([]);
    vi.mocked(categoryService.list).mockResolvedValue([]);
    vi.mocked(vaultService.workspace).mockResolvedValue(page([readyStatement]));
    vi.mocked(vaultService.workspaceStageCounts).mockResolvedValue({ READY_TO_IMPORT: 1 });
    vi.mocked(vaultService.parseImport).mockResolvedValue([
      { date: "2026-01-05", amount: "10.00", type: "EXPENSE", description: "Groceries", dedupKey: "k1" },
    ]);
    const user = userEvent.setup();

    render(<VaultWorkspace />);
    await screen.findByText("jan.csv");
    await user.click(screen.getByRole("button", { name: "Review" }));

    await waitFor(() => expect(vaultService.parseImport).toHaveBeenCalledWith("doc-statement-ready"));
    expect(await screen.findByText("Groceries")).toBeInTheDocument();
    expect(screen.queryByText("jan.csv")).not.toBeInTheDocument();

    await user.click(screen.getByText("Back to list"));
    expect(await screen.findByText("jan.csv")).toBeInTheDocument();
  });

  it("a never-ingested receipt offers to start ingestion", async () => {
    vi.mocked(accountService.list).mockResolvedValue(accounts);
    vi.mocked(agentRunService.list).mockResolvedValue([]);
    vi.mocked(vaultService.workspace).mockResolvedValue(page([unprocessedReceipt]));
    vi.mocked(vaultService.workspaceStageCounts).mockResolvedValue({ NOT_PROCESSED: 1 });

    render(<VaultWorkspace />);
    expect(await screen.findByText("lunch.jpg")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Start ingestion" })).toBeInTheDocument();
  });

  it("empty result under the default stage filter offers to clear filters, when the vault has documents in other stages", async () => {
    vi.mocked(accountService.list).mockResolvedValue(accounts);
    vi.mocked(agentRunService.list).mockResolvedValue([]);
    vi.mocked(vaultService.workspace).mockResolvedValue(page([]));
    // Counts are non-zero (documents exist, just not in a needs-attention stage) — this is a
    // filtered-empty result, not a truly empty vault.
    vi.mocked(vaultService.workspaceStageCounts).mockResolvedValue({ IMPORTED: 4 });

    render(<VaultWorkspace />);
    expect(await screen.findByText("No documents match these filters")).toBeInTheDocument();
  });

  it("a truly empty vault shows the first-run empty state, not the filtered-empty state", async () => {
    vi.mocked(accountService.list).mockResolvedValue(accounts);
    vi.mocked(agentRunService.list).mockResolvedValue([]);
    vi.mocked(vaultService.workspace).mockResolvedValue(page([]));
    vi.mocked(vaultService.workspaceStageCounts).mockResolvedValue({});

    render(<VaultWorkspace />);
    expect(await screen.findByText("Nothing in your vault yet")).toBeInTheDocument();
    expect(screen.queryByText("No documents match these filters")).not.toBeInTheDocument();
  });
});
