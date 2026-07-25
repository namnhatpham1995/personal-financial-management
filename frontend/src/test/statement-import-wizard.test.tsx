/**
 * StatementImportWizard is resume-by-documentId: it always parses (safely re-runnable) the
 * given document and shows the staged rows for review, with an editable per-row category
 * suggestion, rather than owning its own upload step.
 */
import { cleanup, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import { StatementImportWizard } from "@/components/vault/statement-import-wizard";
import { vaultService, type StagedRow } from "@/services/vault-service";
import { categoryService } from "@/services/category-service";
import { renderWithIntl as render } from "@/test/test-utils";

vi.mock("@/services/vault-service");
vi.mock("@/services/category-service");

function renderWizard(documentId = "doc-1") {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <StatementImportWizard documentId={documentId} />
    </QueryClientProvider>
  );
}

const stagedRows: StagedRow[] = [
  { date: "2026-01-05", amount: "12.50", type: "EXPENSE", description: "Coffee", dedupKey: "k1", category: "Dining", categoryId: 7 },
];

describe("StatementImportWizard: resume-by-documentId review", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(categoryService.list).mockResolvedValue([
      { id: 7, name: "Dining", transactionType: "EXPENSE", system: false },
      { id: 8, name: "Salary", transactionType: "INCOME", system: false },
    ]);
  });

  afterEach(() => {
    cleanup();
  });

  it("parses the given documentId on mount and displays its rows with the suggested category", async () => {
    vi.mocked(vaultService.parseImport).mockResolvedValue(stagedRows);
    renderWizard();

    await waitFor(() => {
      expect(screen.getByText("Coffee")).toBeInTheDocument();
    });
    expect(vaultService.parseImport).toHaveBeenCalledWith("doc-1");
    expect(screen.getByRole("combobox")).toHaveValue("7");
  });

  it("a failed rows load is retryable via refetch alone", async () => {
    vi.mocked(vaultService.parseImport)
      .mockRejectedValueOnce(new Error("network error"))
      .mockResolvedValueOnce(stagedRows);
    const user = userEvent.setup();
    renderWizard();

    await screen.findByText("Couldn't load the parsed rows from this statement.");

    await user.click(screen.getByRole("button", { name: "Retry" }));

    await waitFor(() => {
      expect(screen.getByText("Coffee")).toBeInTheDocument();
    });
    expect(vaultService.parseImport).toHaveBeenCalledTimes(2);
  });

  it("confirms selected rows with the (possibly edited) category", async () => {
    vi.mocked(vaultService.parseImport).mockResolvedValue(stagedRows);
    vi.mocked(vaultService.confirmImport).mockResolvedValue(1);
    const user = userEvent.setup();
    renderWizard();

    await screen.findByText("Coffee");

    await user.click(screen.getByRole("button", { name: /Import 1 row/i }));

    await waitFor(() => {
      expect(vaultService.confirmImport).toHaveBeenCalledWith(
        "doc-1",
        [{ dedupKey: "k1", categoryId: 7 }],
        expect.any(String)
      );
    });
  });
});
