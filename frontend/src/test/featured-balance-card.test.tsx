import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ComponentProps } from "react";
import { describe, expect, it, vi } from "vitest";
import { FeaturedBalanceCard } from "@/components/accounts/featured-balance-card";
import type { BalancesSummary, CurrencyBalance } from "@/services/analytics-service";
import { renderWithIntl as render } from "@/test/test-utils";

const multiCurrencyBalances: CurrencyBalance[] = [
  { currency: "USD", totalBalance: "14059.00", accounts: [] },
  { currency: "EUR", totalBalance: "-1210.00", accounts: [] },
  { currency: "VND", totalBalance: "611.00", accounts: [] },
];

const singleCurrencyBalances: CurrencyBalance[] = [
  { currency: "USD", totalBalance: "500.00", accounts: [] },
];

function baseSummary(overrides: Partial<BalancesSummary> = {}): BalancesSummary {
  return {
    buckets: multiCurrencyBalances,
    targetCurrency: "USD",
    convertedTotal: "13500.00",
    rates: [],
    asOf: "2026-07-06T00:00:00Z",
    stale: false,
    ratesUnavailable: false,
    excludedCurrencies: [],
    ...overrides,
  };
}

function renderCard(overrides: Partial<ComponentProps<typeof FeaturedBalanceCard>> = {}) {
  const props: ComponentProps<typeof FeaturedBalanceCard> = {
    balances: multiCurrencyBalances,
    mainCurrency: "USD",
    onMainCurrencyChange: vi.fn(),
    summary: baseSummary(),
    isSummaryLoading: false,
    ...overrides,
  };
  render(<FeaturedBalanceCard {...props} />);
  return props;
}

describe("FeaturedBalanceCard", () => {
  it("renders nothing when there are no balances", () => {
    const { container } = render(
      <FeaturedBalanceCard
        balances={[]}
        mainCurrency={null}
        onMainCurrencyChange={vi.fn()}
        summary={null}
        isSummaryLoading={false}
      />
    );
    expect(container).toBeEmptyDOMElement();
  });

  it("single-currency: shows the native total with no currency dropdown", () => {
    renderCard({
      balances: singleCurrencyBalances,
      mainCurrency: "USD",
      summary: null,
    });

    expect(screen.getByText(/500\.00 USD/)).toBeInTheDocument();
    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
  });

  it("multi-currency: shows a currency dropdown and the converted grand total", () => {
    renderCard();

    expect(screen.getByRole("combobox", { name: "Main currency" })).toBeInTheDocument();
    expect(screen.getByText(/13,500\.00 USD/)).toBeInTheDocument();
    // Native per-currency totals remain listed beneath
    expect(screen.getByText(/14,059\.00 USD/)).toBeInTheDocument();
    expect(screen.getByText(/-1,210\.00 EUR/)).toBeInTheDocument();
    expect(screen.getByText(/611\.00 VND/)).toBeInTheDocument();
  });

  it("selecting a different currency calls onMainCurrencyChange", async () => {
    const user = userEvent.setup();
    const onMainCurrencyChange = vi.fn();
    renderCard({ onMainCurrencyChange });

    await user.selectOptions(screen.getByRole("combobox", { name: "Main currency" }), "EUR");
    expect(onMainCurrencyChange).toHaveBeenCalledWith("EUR");
  });

  it("shows a loading placeholder while the summary is fetching", () => {
    renderCard({ summary: null, isSummaryLoading: true });

    // The converted total isn't known yet — no cached figure should render
    expect(screen.queryByText(/13,500\.00/)).not.toBeInTheDocument();
  });

  it("shows a rates-unavailable notice when conversion partially or fully failed", () => {
    renderCard({
      summary: baseSummary({
        ratesUnavailable: true,
        convertedTotal: "14059.00",
        excludedCurrencies: [
          { currency: "EUR", nativeAmount: -1210 },
          { currency: "VND", nativeAmount: 611 },
        ],
      }),
    });

    expect(screen.getByText(/live rates unavailable/i)).toBeInTheDocument();
    // Excluded currencies are disclosed next to their native amount
    expect(screen.getAllByText("(not converted)")).toHaveLength(2);
  });
});
