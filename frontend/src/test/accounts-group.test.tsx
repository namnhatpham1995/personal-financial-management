import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ComponentProps } from "react";
import { describe, expect, it, vi } from "vitest";
import { AccountsGroup } from "@/components/accounts/balance-breakdown";
import type { Account } from "@/services/account-service";
import { renderWithIntl as render } from "@/test/test-utils";

const accounts: Account[] = [
  {
    id: 1,
    name: "Main Checking",
    accountType: "BANK",
    currency: "USD",
    initialBalance: 1000,
    currentBalance: "1250.50",
    createdAt: "2026-01-01T00:00:00Z",
  },
  {
    id: 2,
    name: "Rewards Card",
    accountType: "CREDIT_CARD",
    currency: "USD",
    initialBalance: 0,
    currentBalance: "240.25",
    createdAt: "2026-01-01T00:00:00Z",
  },
];

function renderGroup(overrides: Partial<ComponentProps<typeof AccountsGroup>> = {}) {
  const props: ComponentProps<typeof AccountsGroup> = {
    accounts,
    onEdit: vi.fn(),
    onDelete: vi.fn(),
    onOpenDetail: vi.fn(),
    ...overrides,
  };
  render(<AccountsGroup {...props} />);
  return props;
}

describe("AccountsGroup", () => {
  it("renders a box per account", () => {
    renderGroup();
    expect(screen.getByText("Main Checking")).toBeInTheDocument();
    expect(screen.getByText("Rewards Card")).toBeInTheDocument();
  });

  it("shows an empty message when this currency has no accounts", () => {
    renderGroup({ accounts: [] });
    expect(screen.getByText("No accounts in this currency yet.")).toBeInTheDocument();
  });

  it("calls edit and delete actions from account boxes", async () => {
    const user = userEvent.setup();
    const onEdit = vi.fn();
    const onDelete = vi.fn();
    renderGroup({ onEdit, onDelete });

    await user.click(screen.getByRole("button", { name: "Edit Rewards Card" }));
    expect(onEdit).toHaveBeenCalledWith(accounts[1]);

    await user.click(screen.getByRole("button", { name: "Delete Rewards Card" }));
    expect(onDelete).toHaveBeenCalledWith(accounts[1]);
  });

  it("opens the account detail view when a box is activated, without triggering it from inner buttons", async () => {
    const user = userEvent.setup();
    const onOpenDetail = vi.fn();
    const onEdit = vi.fn();
    renderGroup({ onOpenDetail, onEdit });

    await user.click(screen.getByRole("button", { name: /View Main Checking details/i }));
    expect(onOpenDetail).toHaveBeenCalledWith(accounts[0]);

    onOpenDetail.mockClear();
    await user.click(screen.getByRole("button", { name: "Edit Rewards Card" }));
    expect(onEdit).toHaveBeenCalledWith(accounts[1]);
    expect(onOpenDetail).not.toHaveBeenCalled();
  });
});
