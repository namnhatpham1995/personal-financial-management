/**
 * Task 4.8: Statement tab IA — per-account filtering, Upload/Import sub-tabs, resuming a
 * previously-staged statement from the Import list, and the Imported bucket collapse/expand.
 */
import { cleanup, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { StatementTab } from "@/components/vault/statement-tab";
import { vaultService, type VaultDocument, type PageResponse } from "@/services/vault-service";
import { accountService, type Account } from "@/services/account-service";
import { categoryService } from "@/services/category-service";
import { renderWithIntl as render } from "@/test/test-utils";

vi.mock("@/services/vault-service");
vi.mock("@/services/account-service");
vi.mock("@/services/category-service");

function renderTab() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <StatementTab />
    </QueryClientProvider>
  );
}

const accounts: Account[] = [
  { id: 1, name: "Checking", accountType: "BANK", currency: "USD", initialBalance: 0, currentBalance: "0", createdAt: "2026-01-01T00:00:00Z" },
];

function page(content: VaultDocument[]): PageResponse<VaultDocument> {
  return { content, totalElements: content.length, totalPages: 1, size: 100, number: 0 };
}

/** Accounts load async, so the option must exist before selecting it. */
async function selectAccount(user: ReturnType<typeof userEvent.setup>, name: string) {
  await waitFor(() => expect(screen.getAllByRole("option", { name })).not.toHaveLength(0));
  await user.selectOptions(screen.getByLabelText("Show files for"), name);
}

const uploadedDoc: VaultDocument = {
  id: "doc-uploaded", accountId: 1, type: "STATEMENT", status: "UPLOADED", source: "manual",
  capturedAt: "2026-01-01T00:00:00Z", hasBinary: true, originalFilename: "jan.csv",
};
const stagedDoc: VaultDocument = {
  id: "doc-staged", accountId: 1, type: "STATEMENT", status: "STAGED", source: "csv",
  capturedAt: "2026-01-02T00:00:00Z", hasBinary: true, originalFilename: "feb.csv",
};
const activeDoc: VaultDocument = {
  id: "doc-active", accountId: 1, type: "STATEMENT", status: "ACTIVE", source: "csv",
  capturedAt: "2026-01-03T00:00:00Z", hasBinary: true, originalFilename: "mar.csv",
};

describe("StatementTab", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(accountService.list).mockResolvedValue(accounts);
    vi.mocked(categoryService.list).mockResolvedValue([]);
  });

  afterEach(() => {
    cleanup();
  });

  it("shows an overall list and narrows it when an account is selected", async () => {
    vi.mocked(vaultService.listByType).mockResolvedValue(page([uploadedDoc]));
    renderTab();

    expect(await screen.findByText("jan.csv")).toBeInTheDocument();

    const user = userEvent.setup();
    await selectAccount(user, "Checking");

    await screen.findByText("jan.csv");
    expect(vaultService.listByType).toHaveBeenCalledWith("STATEMENT", 1, 0, 100);
  });

  it("resumes a previously-staged statement from the Import list's Unimported section", async () => {
    vi.mocked(vaultService.listByType).mockResolvedValue(page([stagedDoc]));
    vi.mocked(vaultService.parseImport).mockResolvedValue([
      { date: "2026-02-01", amount: "10.00", type: "EXPENSE", description: "Groceries", dedupKey: "k1" },
    ]);
    const user = userEvent.setup();
    renderTab();
    await selectAccount(user, "Checking");

    await user.click(screen.getByRole("button", { name: /Import list/ }));
    await screen.findByText("feb.csv");

    await user.click(screen.getByText("feb.csv"));

    await waitFor(() => {
      expect(vaultService.parseImport).toHaveBeenCalledWith("doc-staged");
    });
    expect(await screen.findByText("Groceries")).toBeInTheDocument();
  });

  it("Imported bucket is collapsed by default and expands on click", async () => {
    vi.mocked(vaultService.listByType).mockResolvedValue(page([activeDoc]));
    const user = userEvent.setup();
    renderTab();
    await selectAccount(user, "Checking");
    await user.click(screen.getByRole("button", { name: /Import list/ }));

    await screen.findByText("1 imported statement");
    expect(screen.queryByText("mar.csv")).not.toBeInTheDocument();

    await user.click(screen.getByText("1 imported statement"));
    expect(await screen.findByText("mar.csv")).toBeInTheDocument();
  });
});
