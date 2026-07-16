import { describe, it, expect } from "vitest";
import { validate } from "../graph/nodes/validate.js";
import type { RunState } from "../graph/state.js";
import type { Proposal } from "../schemas.js";

function baseState(overrides: Partial<RunState>): RunState {
  return {
    runId: 1,
    vaultDocumentId: "doc-1",
    agentToken: "token",
    categories: [],
    accounts: [],
    proposals: [],
    ...overrides,
  } as RunState;
}

function proposal(overrides: Partial<Proposal>): Proposal {
  return {
    merchant: "Store",
    date: "2020-01-01",
    amount: "10.00",
    currency: "USD",
    categoryId: 1,
    accountId: 1,
    description: "item",
    flags: [],
    excluded: false,
    ...overrides,
  };
}

describe("validate node", () => {
  it("does not flag a proposal set that reconciles with the extracted total", () => {
    const state = baseState({
      extraction: { merchant: "Store", date: "2020-01-01", currency: "USD", lineItems: [], total: "10.00" },
      proposals: [proposal({ amount: "10.00" })],
    });
    const result = validate(state);
    expect(result.proposals?.[0].flags).toEqual([]);
  });

  it("flags totals-mismatch without altering the amounts", () => {
    const state = baseState({
      extraction: { merchant: "Store", date: "2020-01-01", currency: "USD", lineItems: [], total: "99.00" },
      proposals: [proposal({ amount: "10.00" })],
    });
    const result = validate(state);
    expect(result.proposals?.[0].flags).toContain("totals-mismatch");
    expect(result.proposals?.[0].amount).toBe("10.00");
  });

  it("flags a future-dated proposal", () => {
    const futureDate = new Date(Date.now() + 86_400_000 * 30).toISOString().slice(0, 10);
    const state = baseState({
      extraction: { merchant: "Store", date: futureDate, currency: "USD", lineItems: [], total: "10.00" },
      proposals: [proposal({ amount: "10.00", date: futureDate })],
    });
    const result = validate(state);
    expect(result.proposals?.[0].flags).toContain("future-date");
  });

  it("flags an unrecognized currency", () => {
    const state = baseState({
      extraction: { merchant: "Store", date: "2020-01-01", currency: "XXX", lineItems: [], total: "10.00" },
      proposals: [proposal({ amount: "10.00", currency: "XXX" })],
    });
    const result = validate(state);
    expect(result.proposals?.[0].flags).toContain("unrecognized-currency");
  });
});
