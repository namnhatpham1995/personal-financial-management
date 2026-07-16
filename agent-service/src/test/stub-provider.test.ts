import { describe, it, expect } from "vitest";
import { StubLlmProvider } from "../llm/stub-provider.js";
import { LlmProviderError } from "../llm/provider.js";

function scenario(name: string): Buffer {
  return Buffer.from(`scenario:${name}`);
}

describe("StubLlmProvider.extract", () => {
  const provider = new StubLlmProvider();

  it("extracts a clean receipt into structured fields", async () => {
    const result = await provider.extract(scenario("clean-receipt"), "image/jpeg");
    expect(result.merchant).toBe("Corner Market");
    expect(result.lineItems).toHaveLength(3);
    expect(result.total).toBe("12.50");
  });

  it("throws a non-retryable error for an unparseable receipt", async () => {
    await expect(provider.extract(scenario("unparseable"), "image/jpeg")).rejects.toMatchObject({
      constructor: LlmProviderError,
      retryable: false,
    });
  });

  it("throws a retryable error when the provider fixture simulates an outage", async () => {
    await expect(provider.extract(scenario("provider-error"), "image/jpeg")).rejects.toMatchObject({
      retryable: true,
    });
  });

  it("extracts injection-text as inert line-item data, not as instructions", async () => {
    const result = await provider.extract(scenario("injection-receipt"), "image/jpeg");
    expect(result.lineItems[0].description).toContain("IGNORE ALL PREVIOUS INSTRUCTIONS");
    expect(result.merchant).toBe("Corner Market");
  });
});

describe("StubLlmProvider.categorize", () => {
  const provider = new StubLlmProvider();
  const categories = [{ id: 1, name: "Groceries", transactionType: "EXPENSE" }];
  const accounts = [{ id: 10, name: "Checking", currency: "USD" }];

  it("maps a matching line item to the user's category", async () => {
    const extraction = await provider.extract(scenario("clean-receipt"), "image/jpeg");
    const { proposals } = await provider.categorize({ extraction, categories, accounts });
    expect(proposals).toHaveLength(3);
    expect(proposals.every((p) => p.accountId === 10)).toBe(true);
  });

  it("flags unknown items as low-confidence instead of guessing", async () => {
    const extraction = await provider.extract(scenario("unknown-categories"), "image/jpeg");
    const { proposals } = await provider.categorize({ extraction, categories, accounts });
    expect(proposals[0].categoryId).toBeNull();
    expect(proposals[0].flags).toContain("low-confidence");
  });
});
