import type { AxiosInstance } from "axios";
import { z } from "zod";
import { describe, expect, it, vi } from "vitest";
import { registerAccountTools } from "../tools/accounts.js";
import { registerBudgetTools } from "../tools/budgets.js";
import { registerTransactionTools } from "../tools/transactions.js";
import { registerCategoryTools } from "../tools/categories.js";
import { createAccountShape, createTransactionShape, createTransactionsBatchShape } from "../tools/schemas.js";
import type { ToolResult } from "../tools/tool-result.js";

/** A syntactically valid idempotencyKey/clientRequestId (16-128 URL-safe characters). */
const VALID_KEY = "test-idempotency-key-0001";

type ToolHandler = (params: Record<string, unknown>) => Promise<ToolResult>;

function setupTools() {
  const handlers = new Map<string, ToolHandler>();
  const server = {
    tool: (name: string, _description: string, _schema: unknown, handler: ToolHandler) => {
      handlers.set(name, handler);
    },
  };
  const api = {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
  };

  registerAccountTools(server as never, api as unknown as AxiosInstance);
  registerCategoryTools(server as never, api as unknown as AxiosInstance);
  registerBudgetTools(server as never, api as unknown as AxiosInstance);
  registerTransactionTools(server as never, api as unknown as AxiosInstance);

  return { api, handlers };
}

describe("setup MCP tools", () => {
  it("registers every curated setup tool and no delete capability", () => {
    const { handlers } = setupTools();

    expect([...handlers.keys()]).toEqual(expect.arrayContaining([
      "get_account",
      "list_categories",
      "create_account",
      "update_account",
      "create_category",
      "update_category",
      "create_budget",
      "update_budget",
    ]));
    expect([...handlers.keys()].some((name) => name.startsWith("delete_"))).toBe(false);
  });

  it("maps setup requests to their curated endpoints", async () => {
    const { api, handlers } = setupTools();
    api.get.mockResolvedValue({ data: [{ id: 4, name: "Food" }] });
    api.post.mockResolvedValue({ data: { id: 7 } });
    api.put.mockResolvedValue({ data: { id: 8 } });

    await handlers.get("get_account")!({ id: 3 });
    await handlers.get("list_categories")!({ type: "EXPENSE" });
    await handlers.get("create_account")!({
      idempotencyKey: VALID_KEY, name: "Cash", accountType: "CASH", currency: "EUR",
    });
    await handlers.get("update_category")!({ id: 8, name: "Supermarket" });
    await handlers.get("create_budget")!({
      idempotencyKey: VALID_KEY,
      categoryId: 4,
      period: "MONTHLY",
      amountLimit: 500,
      startDate: "2026-01-01",
      currency: "EUR",
    });
    await handlers.get("update_budget")!({ id: 7, amountLimit: 550 });

    expect(api.get).toHaveBeenNthCalledWith(1, "/accounts/3");
    expect(api.get).toHaveBeenNthCalledWith(2, "/categories", { params: { type: "EXPENSE" } });
    expect(api.post).toHaveBeenNthCalledWith(
      1,
      "/accounts",
      { name: "Cash", accountType: "CASH", currency: "EUR" },
      { headers: { "Idempotency-Key": VALID_KEY } }
    );
    expect(api.put).toHaveBeenCalledWith("/categories/8", { name: "Supermarket" });
    expect(api.post).toHaveBeenNthCalledWith(
      2,
      "/budgets",
      expect.objectContaining({ categoryId: 4, amountLimit: 500 }),
      { headers: { "Idempotency-Key": VALID_KEY } }
    );
    expect(api.put).toHaveBeenCalledWith("/budgets/7", { amountLimit: 550 });

    // idempotencyKey must never leak into the JSON body sent to the backend.
    const [, accountBody] = api.post.mock.calls[0];
    const [, budgetBody] = api.post.mock.calls[1];
    expect(accountBody).not.toHaveProperty("idempotencyKey");
    expect(budgetBody).not.toHaveProperty("idempotencyKey");
  });

  it("returns a credential-safe scope error when a setup write is forbidden", async () => {
    const { api, handlers } = setupTools();
    const secret = "fintrack_pat_should-never-leak";
    api.post.mockRejectedValue({
      isAxiosError: true,
      response: { status: 403, data: { detail: secret } },
      config: { headers: { Authorization: `Bearer ${secret}` } },
      stack: secret,
    });

    const result = await handlers.get("create_category")!({
      idempotencyKey: VALID_KEY, name: "Transport", transactionType: "EXPENSE",
    });

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain("scope does not permit");
    expect(result.content[0].text).not.toContain(secret);
  });

  it("forwards create_category's idempotencyKey as a header and strips it from the body", async () => {
    const { api, handlers } = setupTools();
    api.post.mockResolvedValue({ data: { id: 9, name: "Transport" } });

    await handlers.get("create_category")!({
      idempotencyKey: VALID_KEY, name: "Transport", transactionType: "EXPENSE",
    });

    expect(api.post).toHaveBeenCalledWith(
      "/categories",
      { name: "Transport", transactionType: "EXPENSE" },
      { headers: { "Idempotency-Key": VALID_KEY } }
    );
  });

  it("forwards create_transaction's idempotencyKey as a header and strips it from the body", async () => {
    const { api, handlers } = setupTools();
    api.post.mockResolvedValue({ data: { id: 10 } });

    await handlers.get("create_transaction")!({
      idempotencyKey: VALID_KEY,
      transactionType: "EXPENSE",
      amount: 12,
      transactionDate: "2026-01-01",
      accountId: 1,
    });

    expect(api.post).toHaveBeenCalledWith(
      "/transactions",
      { transactionType: "EXPENSE", amount: 12, transactionDate: "2026-01-01", accountId: 1 },
      { headers: { "Idempotency-Key": VALID_KEY } }
    );
  });

  it("maps batch transaction creation to its dedicated endpoint with the caller-supplied Idempotency-Key", async () => {
    const { api, handlers } = setupTools();
    api.post.mockResolvedValue({ data: { results: [{ rowIndex: 0, status: "CREATED" }] } });
    const batchKey = "batch-idempotency-key-0001";
    await handlers.get("create_transactions_batch")!({
      idempotencyKey: batchKey,
      transactions: [{
        clientRequestId: "row-request-id-0001",
        transaction: { transactionType: "EXPENSE", amount: 12, transactionDate: "2026-01-01", accountId: 1 },
      }],
    });

    expect(api.post).toHaveBeenCalledWith(
      "/transactions/batch",
      {
        transactions: [{
          clientRequestId: "row-request-id-0001",
          transaction: { transactionType: "EXPENSE", amount: 12, transactionDate: "2026-01-01", accountId: 1 },
        }],
      },
      { headers: { "Idempotency-Key": batchKey } }
    );
    // The row-level clientRequestId stays inside the body; only the top-level batch key moves
    // to the header.
    const [, batchBody] = api.post.mock.calls[0];
    expect(batchBody).not.toHaveProperty("idempotencyKey");
    expect(batchBody.transactions[0]).toHaveProperty("clientRequestId", "row-request-id-0001");
  });

  it("treats a replayed success (Idempotency-Replayed: true) as an ordinary success, not an error", async () => {
    const { api, handlers } = setupTools();
    api.post.mockResolvedValue({
      data: { id: 11, name: "Cash" },
      headers: { "idempotency-replayed": "true" },
    });

    const result = await handlers.get("create_account")!({
      idempotencyKey: VALID_KEY, name: "Cash", accountType: "CASH", currency: "EUR",
    });

    expect(result.isError).toBeFalsy();
    expect(JSON.parse(result.content[0].text)).toEqual({ id: 11, name: "Cash" });
  });

  it("rejects a create tool call missing idempotencyKey via local schema validation before any API call", async () => {
    const { api } = setupTools();

    expect(
      z.object(createAccountShape).safeParse({ name: "Cash", accountType: "CASH", currency: "EUR" }).success
    ).toBe(false);
    expect(
      z.object(createTransactionShape).safeParse({
        transactionType: "EXPENSE", amount: 12, transactionDate: "2026-01-01", accountId: 1,
      }).success
    ).toBe(false);
    expect(
      z.object(createTransactionsBatchShape).safeParse({
        transactions: [{
          clientRequestId: "row-request-id-0001",
          transaction: { transactionType: "EXPENSE", amount: 12, transactionDate: "2026-01-01", accountId: 1 },
        }],
      }).success
    ).toBe(false);

    expect(api.post).not.toHaveBeenCalled();
  });
});
