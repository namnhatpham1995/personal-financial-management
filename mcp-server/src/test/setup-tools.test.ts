import type { AxiosInstance } from "axios";
import { describe, expect, it, vi } from "vitest";
import { registerAccountTools } from "../tools/accounts.js";
import { registerBudgetTools } from "../tools/budgets.js";
import { registerTransactionTools } from "../tools/transactions.js";
import { registerCategoryTools } from "../tools/categories.js";
import type { ToolResult } from "../tools/tool-result.js";

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
    await handlers.get("create_account")!({ name: "Cash", accountType: "CASH", currency: "EUR" });
    await handlers.get("update_category")!({ id: 8, name: "Supermarket" });
    await handlers.get("create_budget")!({
      categoryId: 4,
      period: "MONTHLY",
      amountLimit: 500,
      startDate: "2026-01-01",
      currency: "EUR",
    });
    await handlers.get("update_budget")!({ id: 7, amountLimit: 550 });

    expect(api.get).toHaveBeenNthCalledWith(1, "/accounts/3");
    expect(api.get).toHaveBeenNthCalledWith(2, "/categories", { params: { type: "EXPENSE" } });
    expect(api.post).toHaveBeenNthCalledWith(1, "/accounts", { name: "Cash", accountType: "CASH", currency: "EUR" });
    expect(api.put).toHaveBeenCalledWith("/categories/8", { name: "Supermarket" });
    expect(api.post).toHaveBeenNthCalledWith(2, "/budgets", expect.objectContaining({ categoryId: 4, amountLimit: 500 }));
    expect(api.put).toHaveBeenCalledWith("/budgets/7", { amountLimit: 550 });
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

    const result = await handlers.get("create_category")!({ name: "Transport", transactionType: "EXPENSE" });

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toContain("scope does not permit");
    expect(result.content[0].text).not.toContain(secret);
  });

  it("maps batch transaction creation to its dedicated endpoint with a generated Idempotency-Key", async () => {
    const { api, handlers } = setupTools();
    api.post.mockResolvedValue({ data: { results: [{ rowIndex: 0, status: "CREATED" }] } });
    await handlers.get("create_transactions_batch")!({ transactions: [{
      clientRequestId: "row-request-id-0001",
      transaction: { transactionType: "EXPENSE", amount: 12, transactionDate: "2026-01-01", accountId: 1 },
    }] });
    expect(api.post).toHaveBeenCalledWith(
      "/transactions/batch",
      expect.any(Object),
      { headers: { "Idempotency-Key": expect.any(String) } }
    );
  });
});
