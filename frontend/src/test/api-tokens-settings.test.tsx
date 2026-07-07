import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { CreatedTokenBanner } from "@/app/dashboard/settings/api-tokens/created-token-banner";
import { TokenRow } from "@/app/dashboard/settings/api-tokens/token-row";
import type { ApiToken } from "@/services/api-token-service";

const token: ApiToken = {
  id: 1,
  name: "Claude Desktop",
  tokenPrefix: "fintrack_pat_Ab3F",
  scope: "READ",
  createdAt: "2026-01-01T00:00:00Z",
  expiresAt: "2026-04-01T00:00:00Z",
  lastUsedAt: null,
  revoked: false,
};

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("CreatedTokenBanner (one-time plaintext display)", () => {
  // user-event's setup() installs its own navigator.clipboard stub, so the mock must be
  // (re)defined AFTER setup() runs, per test — otherwise setup() silently clobbers it.
  function mockClipboard() {
    const writeTextMock = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText: writeTextMock },
      configurable: true,
    });
    return writeTextMock;
  }

  it("shows the plaintext token and a copy action", () => {
    render(<CreatedTokenBanner plaintextToken="fintrack_pat_abc123" onDismiss={vi.fn()} />);

    expect(screen.getByText("fintrack_pat_abc123")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Copy token" })).toBeInTheDocument();
  });

  it("copies the plaintext to the clipboard", async () => {
    const user = userEvent.setup();
    const writeTextMock = mockClipboard();
    render(<CreatedTokenBanner plaintextToken="fintrack_pat_abc123" onDismiss={vi.fn()} />);

    await user.click(screen.getByRole("button", { name: "Copy token" }));

    expect(writeTextMock).toHaveBeenCalledWith("fintrack_pat_abc123");
    expect(await screen.findByText("Copied")).toBeInTheDocument();
  });

  it("dismisses when Done is clicked, removing the plaintext from view", async () => {
    const user = userEvent.setup();
    const onDismiss = vi.fn();
    render(<CreatedTokenBanner plaintextToken="fintrack_pat_abc123" onDismiss={onDismiss} />);

    await user.click(screen.getByRole("button", { name: "Done" }));

    expect(onDismiss).toHaveBeenCalled();
  });
});

describe("TokenRow (list rendering + revoke confirmation)", () => {
  it("renders name, masked prefix, and scope without ever showing plaintext", () => {
    render(
      <TokenRow
        token={token}
        isConfirmingRevoke={false}
        onRevokeRequest={vi.fn()}
        onRevokeCancel={vi.fn()}
        onRevokeConfirm={vi.fn()}
        isRevokePending={false}
      />
    );

    expect(screen.getByText("Claude Desktop")).toBeInTheDocument();
    expect(screen.getByText("fintrack_pat_Ab3F••••••••")).toBeInTheDocument();
    expect(screen.getByText("Read only")).toBeInTheDocument();
    expect(screen.queryByText(/^fintrack_pat_(?!Ab3F)/)).not.toBeInTheDocument();
  });

  it("shows a revoked badge and hides the revoke button for a revoked token", () => {
    render(
      <TokenRow
        token={{ ...token, revoked: true }}
        isConfirmingRevoke={false}
        onRevokeRequest={vi.fn()}
        onRevokeCancel={vi.fn()}
        onRevokeConfirm={vi.fn()}
        isRevokePending={false}
      />
    );

    expect(screen.getByText("Revoked")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: `Revoke ${token.name}` })).not.toBeInTheDocument();
  });

  it("requests revoke confirmation, then confirms", async () => {
    const user = userEvent.setup();
    const onRevokeRequest = vi.fn();
    render(
      <TokenRow
        token={token}
        isConfirmingRevoke={false}
        onRevokeRequest={onRevokeRequest}
        onRevokeCancel={vi.fn()}
        onRevokeConfirm={vi.fn()}
        isRevokePending={false}
      />
    );

    await user.click(screen.getByRole("button", { name: "Revoke Claude Desktop" }));
    expect(onRevokeRequest).toHaveBeenCalled();
  });

  it("cancels revoke confirmation without calling onRevokeConfirm", async () => {
    const user = userEvent.setup();
    const onRevokeCancel = vi.fn();
    const onRevokeConfirm = vi.fn();
    render(
      <TokenRow
        token={token}
        isConfirmingRevoke={true}
        onRevokeRequest={vi.fn()}
        onRevokeCancel={onRevokeCancel}
        onRevokeConfirm={onRevokeConfirm}
        isRevokePending={false}
      />
    );

    await user.click(screen.getByRole("button", { name: "Cancel" }));

    expect(onRevokeCancel).toHaveBeenCalled();
    expect(onRevokeConfirm).not.toHaveBeenCalled();
  });

  it("confirms revoke when the Revoke button in the confirmation row is clicked", async () => {
    const user = userEvent.setup();
    const onRevokeConfirm = vi.fn();
    render(
      <TokenRow
        token={token}
        isConfirmingRevoke={true}
        onRevokeRequest={vi.fn()}
        onRevokeCancel={vi.fn()}
        onRevokeConfirm={onRevokeConfirm}
        isRevokePending={false}
      />
    );

    await user.click(screen.getByRole("button", { name: "Revoke" }));

    expect(onRevokeConfirm).toHaveBeenCalled();
  });
});
