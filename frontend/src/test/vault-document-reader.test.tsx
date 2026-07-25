/**
 * Task 5.5: DocumentReader dispatches by content type — image, PDF, and text (CSV/OFX) each
 * render through their own path.
 */
import { Blob } from "node:buffer";
import { cleanup, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { describe, expect, it, vi, afterEach, beforeAll, afterAll } from "vitest";
import { DocumentReader } from "@/components/vault/document-reader";
import { vaultService } from "@/services/vault-service";
import { renderWithIntl as render } from "@/test/test-utils";

// jsdom's ambient Blob lacks .text(); Node's buffer.Blob has it and is structurally compatible.

vi.mock("@/services/vault-service");

function renderReader(documentId = "doc-1") {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <DocumentReader documentId={documentId} originalFilename="statement.csv" />
    </QueryClientProvider>
  );
}

describe("DocumentReader", () => {
  beforeAll(() => {
    vi.stubGlobal("URL", { ...URL, createObjectURL: vi.fn().mockReturnValue("blob:mock"), revokeObjectURL: vi.fn() });
  });

  afterAll(() => {
    vi.unstubAllGlobals();
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it("renders an image inline", async () => {
    const blob = new Blob(["fake-bytes"], { type: "image/jpeg" });
    vi.mocked(vaultService.getInlineContent).mockResolvedValue({ blob: blob as unknown as globalThis.Blob, contentType: "image/jpeg" });
    renderReader();

    const img = await screen.findByRole("img");
    expect(img).toHaveAttribute("src", "blob:mock");
  });

  it("renders a PDF via embed with a download fallback", async () => {
    const blob = new Blob(["%PDF-fake"], { type: "application/pdf" });
    vi.mocked(vaultService.getInlineContent).mockResolvedValue({ blob: blob as unknown as globalThis.Blob, contentType: "application/pdf" });
    const { container } = renderReader();

    await screen.findByText("Download instead");
    expect(container.querySelector("embed[type='application/pdf']")).not.toBeNull();
  });

  it("renders CSV/text content as <pre>", async () => {
    const blob = new Blob(["date,amount\n2026-01-01,10.00\n"], { type: "text/csv" });
    vi.mocked(vaultService.getInlineContent).mockResolvedValue({ blob: blob as unknown as globalThis.Blob, contentType: "text/csv" });
    const { container } = renderReader();

    await vi.waitFor(() => {
      expect(container.querySelector("pre")?.textContent).toContain("date,amount");
    });
  });

  it("shows a retry affordance on load failure", async () => {
    vi.mocked(vaultService.getInlineContent).mockRejectedValue(new Error("network error"));
    renderReader();

    expect(await screen.findByText("Couldn't load this document.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Retry" })).toBeInTheDocument();
  });
});
