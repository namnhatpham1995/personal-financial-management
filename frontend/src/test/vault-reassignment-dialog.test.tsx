import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { VaultReassignmentDialog } from "@/components/vault/vault-reassignment-dialog";
import { vaultService, type VaultDocument } from "@/services/vault-service";
import type { Account } from "@/services/account-service";
import { renderWithIntl as render } from "@/test/test-utils";

vi.mock("@/services/vault-service");

const accounts: Account[] = [
  { id: 1, name: "Checking", accountType: "BANK", currency: "USD", initialBalance: 0, currentBalance: "0", createdAt: "2026-01-01T00:00:00Z" },
  { id: 2, name: "Savings", accountType: "SAVINGS", currency: "EUR", initialBalance: 0, currentBalance: "0", createdAt: "2026-01-01T00:00:00Z" },
];

const document: VaultDocument = {
  id: "doc-1",
  accountId: 1,
  type: "STATEMENT",
  status: "ACTIVE",
  source: "csv",
  capturedAt: "2026-01-01T00:00:00Z",
  hasBinary: true,
  originalFilename: "statement.csv",
};

describe("VaultReassignmentDialog", () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it("previews the impact and confirms a move with an idempotency key", async () => {
    vi.mocked(vaultService.previewReassignment).mockResolvedValue({
      documentId: "doc-1",
      type: "STATEMENT",
      originalFilename: "statement.csv",
      sourceAccountId: 1,
      sourceAccountName: "Checking",
      sourceCurrency: "USD",
      targetAccountId: 2,
      targetAccountName: "Savings",
      targetCurrency: "EUR",
      currencyChanged: true,
      importedTransactionCount: 3,
      detachManualLink: true,
    });
    vi.mocked(vaultService.reassign).mockResolvedValue({
      document,
      removedImportedTransactions: 3,
      manualLinkDetached: true,
      replayed: false,
    });

    const user = userEvent.setup();
    render(<VaultReassignmentDialog document={document} accounts={accounts} />);
    await user.click(screen.getByRole("button", { name: "Move" }));
    await user.selectOptions(await screen.findByLabelText("New account"), "2");

    expect(await screen.findByText(/3 imported transactions will be removed/)).toBeInTheDocument();
    expect(screen.getByText(/currency changes from USD to EUR/)).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Move file" }));
    await waitFor(() => expect(vaultService.reassign).toHaveBeenCalledWith("doc-1", 2, expect.any(String)));
  });
});
