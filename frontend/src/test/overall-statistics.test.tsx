import { screen } from "@testing-library/react";
import type { ComponentProps } from "react";
import { describe, expect, it } from "vitest";
import { OverallStatistics } from "@/components/overview/overall-statistics";
import type { ConvertedOverview } from "@/services/analytics-service";
import { renderWithIntl as render } from "@/test/test-utils";

function baseOverview(overrides: Partial<ConvertedOverview> = {}): ConvertedOverview {
  return {
    targetCurrency: "EUR",
    spending: [
      { categoryId: 1, categoryName: "Groceries", totalAmount: 320.5, transactionCount: 6 },
    ],
    trend: [{ year: 2026, month: 6, income: 4000, expense: 2500, net: 1500 }],
    rates: [{ from: "USD", to: "EUR", rate: 0.92 }],
    asOf: "2026-07-06T00:00:00Z",
    ratesUnavailable: false,
    stale: false,
    excludedCurrencies: [],
    ...overrides,
  };
}

function renderStats(overrides: Partial<ComponentProps<typeof OverallStatistics>> = {}) {
  const props: ComponentProps<typeof OverallStatistics> = {
    mainCurrency: "EUR",
    overview: baseOverview(),
    isLoading: false,
    ...overrides,
  };
  render(<OverallStatistics {...props} />);
  return props;
}

describe("OverallStatistics", () => {
  it("renders nothing while loading", () => {
    const { container } = render(
      <OverallStatistics mainCurrency="EUR" overview={null} isLoading={true} />
    );
    expect(container).toBeEmptyDOMElement();
  });

  it("renders nothing when overview data is unavailable and not loading", () => {
    const { container } = render(
      <OverallStatistics mainCurrency="EUR" overview={null} isLoading={false} />
    );
    expect(container).toBeEmptyDOMElement();
  });

  it("renders converted cash flow and spending charts with rate provenance", () => {
    renderStats();

    expect(screen.getByText("Overall")).toBeInTheDocument();
    expect(screen.getByText("Cash Flow")).toBeInTheDocument();
    expect(screen.getByText("Spending by Category")).toBeInTheDocument();
    expect(screen.getByText(/Rates as of/)).toBeInTheDocument();
  });

  it("flags stale rates without hiding the charts", () => {
    renderStats({ overview: baseOverview({ stale: true }) });

    expect(screen.getByText(/Exchange rates may be outdated/i)).toBeInTheDocument();
    expect(screen.getByText("Cash Flow")).toBeInTheDocument();
  });

  it("shows a rates-unavailable notice instead of charts when conversion failed", () => {
    renderStats({ overview: baseOverview({ ratesUnavailable: true }) });

    expect(
      screen.getByText(/Live rates unavailable — showing per-currency figures below\./)
    ).toBeInTheDocument();
    expect(screen.queryByText("Cash Flow")).not.toBeInTheDocument();
    expect(screen.queryByText("Spending by Category")).not.toBeInTheDocument();
  });
});
