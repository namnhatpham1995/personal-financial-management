import { z } from "zod";
import { describe, expect, it } from "vitest";
import {
  createAccountShape,
  createTransactionsBatchShape,
  createBudgetShape,
  createCategoryShape,
  createTransactionShape,
  budgetHistoryShape,
  getAccountShape,
  getTransactionShape,
  listCategoriesShape,
  listTransactionsShape,
  updateAccountShape,
  updateBudgetShape,
  updateCategoryShape,
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

  it("create_transaction accepts a cross-currency TRANSFER with destinationAmount", () => {
    const result = z.object(createTransactionShape).safeParse({
      transactionType: "TRANSFER",
      amount: 500,
      destinationAmount: 14600000,
      transactionDate: "2026-01-01",
      accountId: 1,
      transferAccountId: 2,
    });
    expect(result.success).toBe(true);
  });

  it("create_transaction rejects a non-positive destinationAmount", () => {
    const result = z.object(createTransactionShape).safeParse({
      transactionType: "TRANSFER",
      amount: 500,
      destinationAmount: 0,
      transactionDate: "2026-01-01",
      accountId: 1,
      transferAccountId: 2,
    });
    expect(result.success).toBe(false);
  });

  it("update_transaction accepts amount and destinationAmount supplied together", () => {
    const result = z.object(updateTransactionShape).safeParse({
      id: 1,
      amount: 600,
      destinationAmount: 17520000,
    });
    expect(result.success).toBe(true);
  });

  it("create_transactions_batch accepts a row with destinationAmount", () => {
    const result = z.object(createTransactionsBatchShape).safeParse({
      transactions: [{
        transactionType: "TRANSFER",
        amount: 500,
        destinationAmount: 14600000,
        transactionDate: "2026-01-01",
        accountId: 1,
        transferAccountId: 2,
      }],
    });
    expect(result.success).toBe(true);
  });

  it("get_account rejects unknown fields", () => {
    const result = z.object(getAccountShape).strict().safeParse({ id: 1, path: "/vault" });
    expect(result.success).toBe(false);
  });

  it("create_account rejects an invalid currency and negative initial balance", () => {
    const result = z.object(createAccountShape).safeParse({
      name: "EUR checking",
      accountType: "BANK",
      currency: "eur",
      initialBalance: -1,
    });
    expect(result.success).toBe(false);
  });

  it("update_account accepts a partial non-destructive update", () => {
    const result = z.object(updateAccountShape).safeParse({ id: 1, name: "Main account" });
    expect(result.success).toBe(true);
  });

  it("list_categories rejects an invalid transaction type", () => {
    const result = z.object(listCategoriesShape).safeParse({ type: "DELETE" });
    expect(result.success).toBe(false);
  });

  it("category schemas enforce a name and allow an optional update type", () => {
    expect(z.object(createCategoryShape).safeParse({ name: "", transactionType: "EXPENSE" }).success).toBe(false);
    expect(z.object(updateCategoryShape).safeParse({ id: 1, name: "Groceries" }).success).toBe(true);
  });

  it("budget schemas require positive amounts, ISO currency, and valid periods", () => {
    expect(z.object(createBudgetShape).safeParse({
      categoryId: 1,
      period: "WEEKLY",
      amountLimit: 0,
      startDate: "2026-01-01",
      currency: "EURO",
    }).success).toBe(false);
    expect(z.object(updateBudgetShape).safeParse({ id: 1, amountLimit: 120, period: "MONTHLY" }).success).toBe(true);
  });

  it("batch transactions reject empty batches and unknown row fields", () => {
    expect(z.object(createTransactionsBatchShape).safeParse({ transactions: [] }).success).toBe(false);
    expect(z.object(createTransactionsBatchShape).safeParse({ transactions: [{
      transactionType: "EXPENSE", amount: 5, transactionDate: "2026-01-01", accountId: 1, unsafe: true,
    }] }).success).toBe(false);
  });

  it("budget history accepts valid ranges and rejects malformed currencies and dates", () => {
    expect(z.object(budgetHistoryShape).strict().safeParse({
      from: "2026-01-01", to: "2026-03-31", currency: "EUR",
    }).success).toBe(true);
    expect(z.object(budgetHistoryShape).strict().safeParse({
      from: "2026/01/01", to: "2026-03-31", currency: "eur",
    }).success).toBe(false);
  });
});
