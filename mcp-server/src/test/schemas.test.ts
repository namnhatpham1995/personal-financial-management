import { z } from "zod";
import { describe, expect, it } from "vitest";
import {
  createTransactionShape,
  getTransactionShape,
  listTransactionsShape,
  updateTransactionShape,
} from "../tools/schemas.js";

/**
 * These validate the exact zod shapes the MCP SDK uses to gate a tool call before our
 * handler (and therefore any API request) ever runs — proving invalid input never
 * reaches the network, without needing the full MCP server/transport plumbing.
 */
describe("tool input schemas reject malformed input before any API call", () => {
  it("list_transactions rejects an unknown field", () => {
    const result = z.object(listTransactionsShape).strict().safeParse({ accountId: 1, evil: "x" });
    expect(result.success).toBe(false);
  });

  it("list_transactions rejects a malformed date", () => {
    const result = z.object(listTransactionsShape).safeParse({ startDate: "not-a-date" });
    expect(result.success).toBe(false);
  });

  it("get_transaction rejects a non-numeric id", () => {
    const result = z.object(getTransactionShape).safeParse({ id: "abc" });
    expect(result.success).toBe(false);
  });

  it("create_transaction rejects a negative amount", () => {
    const result = z.object(createTransactionShape).safeParse({
      transactionType: "EXPENSE",
      amount: -5,
      transactionDate: "2026-01-01",
      accountId: 1,
    });
    expect(result.success).toBe(false);
  });

  it("create_transaction rejects an invalid transactionType", () => {
    const result = z.object(createTransactionShape).safeParse({
      transactionType: "DELETE_EVERYTHING",
      amount: 10,
      transactionDate: "2026-01-01",
      accountId: 1,
    });
    expect(result.success).toBe(false);
  });

  it("create_transaction accepts a valid payload", () => {
    const result = z.object(createTransactionShape).safeParse({
      transactionType: "EXPENSE",
      amount: 12.5,
      transactionDate: "2026-01-01",
      accountId: 1,
      note: "Coffee",
    });
    expect(result.success).toBe(true);
  });

  it("update_transaction rejects a note over the length limit", () => {
    const result = z.object(updateTransactionShape).safeParse({
      id: 1,
      note: "x".repeat(2001),
    });
    expect(result.success).toBe(false);
  });
});
