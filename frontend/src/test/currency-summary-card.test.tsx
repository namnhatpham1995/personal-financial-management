import { screen } from "@testing-library/react";
import type { ComponentProps } from "react";
import { describe, expect, it } from "vitest";
import { CurrencySummaryCard } from "@/components/overview/currency-summary-card";
import { renderWithIntl as render } from "@/test/test-utils";

function renderCard(overrides: Partial<ComponentProps<typeof CurrencySummaryCard>> = {}) {
  const props: ComponentProps<typeof CurrencySummaryCard> = {
    currency: "EUR",
    nativeTotal: "1250.50",
    accountCount: 2,
    netCashFlow: 300,
    isOverBudget: false,
    ...overrides,
  };
  render(<CurrencySummaryCard {...props} />);
  return props;
}

describe("CurrencySummaryCard", () => {
  it("renders native amounts only — currency code, total, account count, net cash flow", () => {
    renderCard();
    expect(screen.getByText("EUR")).toBeInTheDocument();
    expect(screen.getByText("1,250.50 EUR")).toBeInTheDocument();
    expect(screen.getByText("2 accounts")).toBeInTheDocument();
    expect(screen.getByText("300.00 EUR")).toBeInTheDocument();
  });

  it("is a keyboard-accessible link to the currency's detail route", () => {
    renderCard({ currency: "USD" });
    expect(screen.getByRole("link", { name: /USD/ })).toHaveAttribute(
      "href",
      "/dashboard/currency/USD"
    );
  });

  it("omits the native total when the currency has no accounts", () => {
    renderCard({ nativeTotal: undefined, accountCount: 0 });
    expect(screen.queryByText("1,250.50 EUR")).not.toBeInTheDocument();
    expect(screen.getByText("No accounts")).toBeInTheDocument();
  });

  it("shows an over-budget indicator combining an icon with a text label", () => {
    renderCard({ isOverBudget: true });
    expect(screen.getByText("Over budget")).toBeInTheDocument();
  });

  it("does not show the over-budget indicator when under budget", () => {
    renderCard({ isOverBudget: false });
    expect(screen.queryByText("Over budget")).not.toBeInTheDocument();
  });
});
