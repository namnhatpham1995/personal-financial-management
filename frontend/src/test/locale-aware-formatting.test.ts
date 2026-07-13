/**
 * Locale-aware formatting tests (task 2.4).
 * Proves formatAmount/formatCurrency/formatDate/formatRate follow the active
 * locale's grouping/decimal/date conventions while keeping the ISO-code-suffix
 * currency contract, and default to English when no locale is passed.
 */
import { describe, it, expect } from "vitest";
import { formatAmount, formatCurrency, formatDate, formatRate } from "@/lib/utils";

describe("formatAmount locale awareness", () => {
  it("defaults to English grouping when locale is omitted", () => {
    expect(formatAmount(1500)).toBe("1,500.00");
  });

  it("uses German grouping/decimal separators when locale is de", () => {
    expect(formatAmount(1500, "de")).toBe("1.500,00");
  });
});

describe("formatCurrency locale awareness", () => {
  it("keeps the ISO-code suffix under English formatting", () => {
    expect(formatCurrency(1500, "USD")).toBe("1,500.00 USD");
  });

  it("keeps the ISO-code suffix under German formatting", () => {
    expect(formatCurrency(1500, "USD", "de")).toBe("1.500,00 USD");
  });
});

describe("formatDate locale awareness", () => {
  it("formats in English medium style by default", () => {
    expect(formatDate("2026-07-12")).toMatch(/Jul 12, 2026/);
  });

  it("formats using German date conventions when locale is de", () => {
    const result = formatDate("2026-07-12", "de");
    expect(result).toMatch(/12\..*2026/);
  });
});

describe("formatRate locale awareness", () => {
  it("uses German decimal separator for the rate value", () => {
    const result = formatRate("VND", "USD", 0.0000403, "de");
    expect(result).toContain("0,0000403");
  });
});
