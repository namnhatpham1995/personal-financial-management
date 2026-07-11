import type { AxiosInstance } from "axios";
import { describe, expect, it, vi } from "vitest";
import { getBudgetHistory } from "../tools/analytics.js";

describe("historical budget analytics tool", () => {
  it("maps a valid range and currency filter to the curated endpoint", async () => {
    const api = { get: vi.fn().mockResolvedValue({ data: [{ currency: "EUR", spent: 25 }] }) };

    const result = await getBudgetHistory(api as unknown as AxiosInstance, {
      from: "2026-01-01", to: "2026-03-31", currency: "EUR",
    });

    expect(api.get).toHaveBeenCalledWith("/analytics/budget-history", {
      params: { from: "2026-01-01", to: "2026-03-31", currency: "EUR" },
    });
    expect(JSON.parse(result.content[0].text)).toEqual([{ currency: "EUR", spent: 25 }]);
  });

  it("rejects a reversed range without making an API request", async () => {
    const api = { get: vi.fn() };

    const result = await getBudgetHistory(api as unknown as AxiosInstance, {
      from: "2026-04-01", to: "2026-03-31",
    });

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain("from must be on or before to");
    expect(api.get).not.toHaveBeenCalled();
  });
});
