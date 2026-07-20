/**
 * Task 7.4: statement upload and staged-row loading have independent state.
 * A failed rows-GET must be retryable via the rows query alone (refetch()), without
 * re-triggering the upload mutation — proven here by asserting the upload service fn
 * is called exactly once even after the rows-GET is retried.
 */
import { cleanup, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { StatementImportWizard } from "@/components/vault/statement-import-wizard";
import { vaultService, type StagedRow } from "@/services/vault-service";
import { renderWithIntl as render } from "@/test/test-utils";

vi.mock("@/services/vault-service");

function renderWizard() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <StatementImportWizard accountId={1} />
    </QueryClientProvider>
  );
}

const file = new File(["date,amount"], "statement.csv", { type: "text/csv" });

const stagedRows: StagedRow[] = [
  { date: "2026-01-05", amount: "12.50", type: "EXPENSE", description: "Coffee", dedupKey: "k1" },
];

async function uploadFile(user: ReturnType<typeof userEvent.setup>) {
  const input = document.querySelector('input[type="file"]') as HTMLInputElement;
  await user.upload(input, file);
}

describe("StatementImportWizard: upload vs. staged-row loading state", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(vaultService.importUpload).mockResolvedValue("doc-1");
  });

  afterEach(() => {
    cleanup();
  });

  it("retains documentId after a successful upload and transitions to rows once the query resolves", async () => {
    vi.mocked(vaultService.getImportRows).mockResolvedValue(stagedRows);
    const user = userEvent.setup();
    renderWizard();

    await uploadFile(user);

    await waitFor(() => {
      expect(screen.getByText("Coffee")).toBeInTheDocument();
    });
    expect(vaultService.importUpload).toHaveBeenCalledTimes(1);
    expect(vaultService.getImportRows).toHaveBeenCalledWith("doc-1");
  });

  it("a failed rows-GET does not re-trigger the upload and is retryable via the query alone", async () => {
    vi.mocked(vaultService.getImportRows)
      .mockRejectedValueOnce(new Error("network error"))
      .mockResolvedValueOnce(stagedRows);
    const user = userEvent.setup();
    renderWizard();

    await uploadFile(user);

    await screen.findByText("Couldn't load the parsed rows from this upload.");
    expect(vaultService.importUpload).toHaveBeenCalledTimes(1);

    await user.click(screen.getByRole("button", { name: "Retry" }));

    await waitFor(() => {
      expect(screen.getByText("Coffee")).toBeInTheDocument();
    });

    // Retrying the rows fetch never re-uploaded the file.
    expect(vaultService.importUpload).toHaveBeenCalledTimes(1);
    expect(vaultService.getImportRows).toHaveBeenCalledTimes(2);
  });
});
