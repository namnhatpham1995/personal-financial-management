/**
 * Task 4.4: proves the upload flow is decoupled from the workspace's list filter —
 * preselection from a filtered view, mandatory explicit choice from the unfiltered view, and
 * that a filter change made elsewhere does not retroactively change an already-open panel's
 * selected destination account.
 */
import { cleanup, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { VaultUploadPanel } from "@/components/vault/vault-upload-panel";
import type { Account } from "@/services/account-service";
import { renderWithIntl } from "@/test/test-utils";

vi.mock("@/services/vault-service");

const accounts: Account[] = [
  { id: 1, name: "Checking", accountType: "BANK", currency: "USD", initialBalance: 0, currentBalance: "0", createdAt: "2026-01-01T00:00:00Z" },
  { id: 2, name: "Savings", accountType: "SAVINGS", currency: "USD", initialBalance: 0, currentBalance: "0", createdAt: "2026-01-01T00:00:00Z" },
];

describe("VaultUploadPanel", () => {
  afterEach(() => cleanup());

  it("preselects the account from an active single-account filter and shows the dropzone immediately", async () => {
    renderWithIntl(
      <VaultUploadPanel accounts={accounts} defaultAccountId={1} onUploaded={vi.fn()} onClose={vi.fn()} />
    );

    expect(await screen.findByLabelText("Upload to account")).toHaveValue("1");
    // Statement is the default type — its dropzone should already be visible, no extra click needed.
    expect(screen.getByText("Upload bank statement")).toBeInTheDocument();
  });

  it("requires an explicit account choice when opened from the unfiltered (all accounts) view", async () => {
    renderWithIntl(
      <VaultUploadPanel accounts={accounts} defaultAccountId={null} onUploaded={vi.fn()} onClose={vi.fn()} />
    );

    expect(await screen.findByLabelText("Upload to account")).toHaveValue("");
    expect(screen.getByText("Choose an account before uploading a file.")).toBeInTheDocument();
    expect(screen.queryByText("Upload bank statement")).not.toBeInTheDocument();

    const user = userEvent.setup();
    await user.selectOptions(screen.getByLabelText("Upload to account"), "2");
    expect(await screen.findByText("Upload bank statement")).toBeInTheDocument();
  });

  it("a filter change elsewhere does not retroactively change an already-open panel's selection", async () => {
    const { rerender } = renderWithIntl(
      <VaultUploadPanel accounts={accounts} defaultAccountId={1} onUploaded={vi.fn()} onClose={vi.fn()} />
    );
    expect(await screen.findByLabelText("Upload to account")).toHaveValue("1");

    // Simulate the workspace's account filter changing to a different account while this
    // panel remains open — defaultAccountId is only a seed, not a controlled value.
    rerender(<VaultUploadPanel accounts={accounts} defaultAccountId={2} onUploaded={vi.fn()} onClose={vi.fn()} />);

    expect(screen.getByLabelText("Upload to account")).toHaveValue("1");
  });

  it("the user can still change the preselected account before uploading", async () => {
    renderWithIntl(
      <VaultUploadPanel accounts={accounts} defaultAccountId={1} onUploaded={vi.fn()} onClose={vi.fn()} />
    );
    const user = userEvent.setup();
    await user.selectOptions(await screen.findByLabelText("Upload to account"), "2");
    expect(screen.getByLabelText("Upload to account")).toHaveValue("2");
  });
});
