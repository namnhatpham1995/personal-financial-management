import axios, { isAxiosError } from "axios";
import { describe, expect, it } from "vitest";
import { createApiClient } from "../api-client.js";
import { createAccount, listAccounts } from "../tools/accounts.js";
import { getBudgetHistory } from "../tools/analytics.js";
import { createBudget } from "../tools/budgets.js";
import { createCategory } from "../tools/categories.js";
import { createTransaction } from "../tools/transactions.js";

const BACKEND_URL = process.env.FINTRACK_TEST_BACKEND_URL;

/**
 * A raw AxiosError embeds its request config (including transformRequest
 * functions), which Vitest's worker pool cannot structured-clone when
 * reporting a failure -- an uncaught AxiosError here surfaces as an opaque
 * "DataCloneError" with no indication of the real HTTP failure underneath.
 * Setup calls are wrapped through this so a real failure (wrong status,
 * validation error, etc.) is always reported as a plain, readable Error.
 */
async function unwrapAxiosErrors<T>(fn: () => Promise<T>): Promise<T> {
  try {
    return await fn();
  } catch (err) {
    if (isAxiosError(err)) {
      throw new Error(
        `Backend setup call failed: ${err.config?.method?.toUpperCase()} ${err.config?.url} -> ` +
          `${err.response?.status} ${JSON.stringify(err.response?.data)}`
      );
    }
    throw err;
  }
}

/**
 * Verifies the MCP tool -> PAT auth -> real backend chain that every other
 * mcp-server test mocks out. Drives the real list_accounts handler with a
 * real axios client built by createApiClient (the same header-injection code
 * path index.ts wires up at startup), authenticated with a PAT minted through
 * the real backend's own API.
 *
 * Earlier revisions of this test spawned the built server over the stdio
 * transport via the MCP SDK client, to also exercise process spawning and
 * config parsing end to end. That produced an unexplained CI-only failure
 * (an unhandled error with no stack, no in-process handler ever fired, OOM
 * ruled out, and the exact spawn/connect/callTool/close sequence reproduced
 * cleanly outside CI on both plain Node and Vitest) that couldn't be pinned
 * down without live CI shell access. Calling the handler directly keeps the
 * real-backend/real-PAT coverage without the OS-level process boundary.
 *
 * Requires a running Fintrack backend at FINTRACK_TEST_BACKEND_URL. Skipped
 * locally when the env var is unset so the default `npm test` stays fast and
 * offline-safe; the CI `mcp-integration` job sets it after provisioning the
 * backend.
 */
describe.skipIf(!BACKEND_URL)("MCP tool <-> real backend", () => {
  it("list_accounts returns real backend data via a PAT-authenticated client", async () => {
    const email = `mcp-integration-${Date.now()}@test.com`;
    const backend = axios.create({ baseURL: `${BACKEND_URL}/api/v1` });

    const registerResponse = await unwrapAxiosErrors(() =>
      backend.post("/auth/register", {
        email,
        password: "pass1234",
        firstName: "MCP",
        lastName: "Integration",
      })
    );
    const jwt: string = registerResponse.data.accessToken;

    const accountResponse = await unwrapAxiosErrors(() =>
      backend.post(
        "/accounts",
        { name: "MCP Test Account", accountType: "BANK", currency: "USD", initialBalance: "250.00" },
        { headers: { Authorization: `Bearer ${jwt}` } }
      )
    );
    const accountId: number = accountResponse.data.id;

    const tokenResponse = await unwrapAxiosErrors(() =>
      backend.post(
        "/tokens",
        { name: "MCP Integration Test Token", scope: "READ", expiryDays: 30 },
        { headers: { Authorization: `Bearer ${jwt}` } }
      )
    );
    const pat: string = tokenResponse.data.plaintextToken;

    const api = createApiClient({ apiUrl: BACKEND_URL!, apiToken: pat });
    const result = await listAccounts(api);

    expect(result.isError).toBeFalsy();
    expect(result.content).toHaveLength(1);
    expect(result.content[0].type).toBe("text");

    const accounts = JSON.parse(result.content[0].text);
    expect(Array.isArray(accounts)).toBe(true);
    const created = accounts.find((a: { id: number }) => a.id === accountId);
    expect(created).toBeDefined();
    expect(created.name).toBe("MCP Test Account");
    expect(created.currency).toBe("USD");

    // The tool result must never contain the PAT or the JWT
    expect(result.content[0].text).not.toContain(pat);
    expect(result.content[0].text).not.toContain(jwt);
  });

  it("list_accounts surfaces a credential-safe error for an invalid PAT", async () => {
    const api = createApiClient({ apiUrl: BACKEND_URL!, apiToken: "fintrack_pat_invalid00000000000000000000" });
    const result = await listAccounts(api);

    expect(result.isError).toBe(true);
    expect(result.content[0].text).toMatch(/invalid, expired, or revoked/i);
  });

  it("creates an account, category, and budget through a write-scoped PAT", async () => {
    const suffix = Date.now();
    const email = `mcp-setup-${suffix}@test.com`;
    const backend = axios.create({ baseURL: `${BACKEND_URL}/api/v1` });

    const registerResponse = await unwrapAxiosErrors(() =>
      backend.post("/auth/register", {
        email,
        password: "pass1234",
        firstName: "MCP",
        lastName: "Setup",
      })
    );
    const jwt: string = registerResponse.data.accessToken;

    const tokenResponse = await unwrapAxiosErrors(() =>
      backend.post(
        "/tokens",
        { name: "MCP Setup Write Token", scope: "WRITE", expiryDays: 30 },
        { headers: { Authorization: `Bearer ${jwt}` } }
      )
    );
    const pat: string = tokenResponse.data.plaintextToken;
    const api = createApiClient({ apiUrl: BACKEND_URL!, apiToken: pat });

    const accountResult = await createAccount(api, {
      name: `MCP setup account ${suffix}`,
      accountType: "BANK",
      currency: "EUR",
      initialBalance: 250,
    });
    const categoryResult = await createCategory(api, {
      name: `MCP setup category ${suffix}`,
      transactionType: "EXPENSE",
    });

    expect(accountResult.isError).toBeFalsy();
    expect(categoryResult.isError).toBeFalsy();
    const account = JSON.parse(accountResult.content[0].text) as { id: number; currency: string };
    const category = JSON.parse(categoryResult.content[0].text) as { id: number; name: string };

    const budgetResult = await createBudget(api, {
      categoryId: category.id,
      period: "MONTHLY",
      amountLimit: 500,
      startDate: "2026-01-01",
      currency: "EUR",
    });

    expect(budgetResult.isError).toBeFalsy();
    const budget = JSON.parse(budgetResult.content[0].text) as {
      categoryId: number;
      currency: string;
      amountLimit: number;
    };
    expect(account.currency).toBe("EUR");
    expect(category.name).toBe(`MCP setup category ${suffix}`);
    expect(budget.categoryId).toBe(category.id);
    expect(budget.currency).toBe("EUR");
    expect(budget.amountLimit).toBe(500);

    const transactionResult = await createTransaction(api, {
      transactionType: "EXPENSE",
      amount: 25,
      transactionDate: "2026-01-15",
      accountId: account.id,
      categoryId: category.id,
    });
    expect(transactionResult.isError).toBeFalsy();

    const historyResult = await getBudgetHistory(api, {
      from: "2026-01-01",
      to: "2026-02-28",
      currency: "EUR",
    });
    expect(historyResult.isError).toBeFalsy();
    const history = JSON.parse(historyResult.content[0].text) as Array<{
      currency: string;
      periodStart: string;
      spent: number;
    }>;
    expect(history).toHaveLength(2);
    expect(history[0]).toMatchObject({ currency: "EUR", periodStart: "2026-01-01", spent: 25 });
    expect(history[1]).toMatchObject({ currency: "EUR", periodStart: "2026-02-01", spent: 0 });

    for (const result of [accountResult, categoryResult, budgetResult, transactionResult, historyResult]) {
      expect(result.content[0].text).not.toContain(pat);
      expect(result.content[0].text).not.toContain(jwt);
    }
  });
});
