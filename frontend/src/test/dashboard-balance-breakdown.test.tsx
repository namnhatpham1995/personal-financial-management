import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ComponentProps } from "react";
import { describe, expect, it, vi } from "vitest";
import { BalanceBreakdown } from "@/components/accounts/balance-breakdown";
import { renderWithIntl as render } from "@/test/test-utils";

function renderBreakdown(overrides: Partial<ComponentProps<typeof BalanceBreakdown>> = {}) {
  const props: ComponentProps<typeof BalanceBreakdown> = {
    hasAccounts: true,
    showCreateForm: false,
    isCreating: false,
    onAdd: vi.fn(),
    onCreate: vi.fn(),
    onCancelCreate: vi.fn(),
    ...overrides,
  };

  render(<BalanceBreakdown {...props} />);
  return props;
}

describe("BalanceBreakdown", () => {
  it("renders the Accounts heading and add-account action", () => {
    renderBreakdown();

    expect(screen.getByText("Accounts")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Add account" })).toBeInTheDocument();
  });

  it("shows an empty state with an add-account action when there are no accounts", async () => {
    const user = userEvent.setup();
    const onAdd = vi.fn();
    renderBreakdown({ hasAccounts: false, onAdd });

    expect(screen.getByText("No accounts yet")).toBeInTheDocument();
    expect(
      screen.getByText("Add a cash, bank, savings, or credit account to start tracking balances.")
    ).toBeInTheDocument();

    await user.click(screen.getAllByRole("button", { name: "Add account" })[0]);
    expect(onAdd).toHaveBeenCalledTimes(1);
  });

  it("does not show the empty state once accounts exist", () => {
    renderBreakdown({ hasAccounts: true });
    expect(screen.queryByText("No accounts yet")).not.toBeInTheDocument();
  });

  it("submits a new account from the inline Overview form", async () => {
    const user = userEvent.setup();
    const onCreate = vi.fn();
    renderBreakdown({ showCreateForm: true, onCreate });

    const form = screen.getByRole("heading", { name: "Add account" }).closest("div");
    expect(form).not.toBeNull();

    await user.type(within(form as HTMLElement).getByLabelText("Name"), "Brokerage");
    await user.clear(within(form as HTMLElement).getByLabelText("Initial balance"));
    await user.type(within(form as HTMLElement).getByLabelText("Initial balance"), "42");
    await user.click(within(form as HTMLElement).getByRole("button", { name: "Save" }));

    await waitFor(() => {
      expect(onCreate).toHaveBeenCalled();
      expect(onCreate.mock.calls[0][0]).toEqual(
        expect.objectContaining({
          name: "Brokerage",
          currency: "USD",
          initialBalance: 42,
        })
      );
    });
  });
});
