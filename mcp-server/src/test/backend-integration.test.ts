import { randomUUID } from "node:crypto";
import axios, { isAxiosError } from "axios";
import { describe, expect, it } from "vitest";
import { createApiClient } from "../api-client.js";
import { createAccount, listAccounts } from "../tools/accounts.js";
import { getBudgetHistory } from "../tools/analytics.js";
import { createBudget } from "../tools/budgets.js";
import { createCategory } from "../tools/categories.js";
import { createTransaction, createTransactionsBatch } from "../tools/transactions.js";

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
      idempotencyKey: randomUUID(),
      name: `MCP setup account ${suffix}`,
      accountType: "BANK",
      currency: "EUR",
      initialBalance: 250,
    });
    const categoryResult = await createCategory(api, {
      idempotencyKey: randomUUID(),
      name: `MCP setup category ${suffix}`,
      transactionType: "EXPENSE",
    });

    expect(accountResult.isError).toBeFalsy();
    expect(categoryResult.isError).toBeFalsy();
    const account = JSON.parse(accountResult.content[0].text) as { id: number; currency: string };
    const category = JSON.parse(categoryResult.content[0].text) as { id: number; name: string };

    const budgetResult = await createBudget(api, {
      idempotencyKey: randomUUID(),
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
      idempotencyKey: randomUUID(),
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

  /**
   * Full multi-currency agent persona: mirrors how a real agent would use the curated MCP
   * surface end to end, not any single tool in isolation. A write-scoped, long-lived PAT
   * (365 days -- the realistic lifetime for a standing agent integration) sets up accounts
   * in three currencies with very different scales (EUR/USD minor units vs VND's none),
   * batch-enters several months of transactions in one call each, corrects one entry the
   * way an agent would fix a miskeyed amount, and reads back historical budget performance
   * per currency. This is the change's own "MCP persona smoke test" (tasks 6.1/6.2) captured
   * as a repeatable CI check instead of a one-off manual run.
   */
  it("runs a full multi-currency persona: setup, batch entry, correction, and budget history", async () => {
    const suffix = Date.now();
    const email = `mcp-persona-${suffix}@test.com`;
    const backend = axios.create({ baseURL: `${BACKEND_URL}/api/v1` });

    const registerResponse = await unwrapAxiosErrors(() =>
      backend.post("/auth/register", {
        email,
        password: "pass1234",
        firstName: "MCP",
        lastName: "Persona",
      })
    );
    const jwt: string = registerResponse.data.accessToken;

    const tokenResponse = await unwrapAxiosErrors(() =>
      backend.post(
        "/tokens",
        { name: "MCP Persona Token", scope: "WRITE", expiryDays: 365 },
        { headers: { Authorization: `Bearer ${jwt}` } }
      )
    );
    const pat: string = tokenResponse.data.plaintextToken;
    const api = createApiClient({ apiUrl: BACKEND_URL!, apiToken: pat });

    const currencies = ["EUR", "USD", "VND"] as const;
    const accountsByCurrency: Record<(typeof currencies)[number], { id: number }> = {} as never;
    for (const currency of currencies) {
      const result = await createAccount(api, {
        idempotencyKey: randomUUID(),
        name: `Persona ${currency} account ${suffix}`,
        accountType: "BANK",
        currency,
        initialBalance: currency === "VND" ? 20_000_000 : 1_000,
      });
      expect(result.isError).toBeFalsy();
      accountsByCurrency[currency] = JSON.parse(result.content[0].text) as { id: number };
    }

    const categoryResult = await createCategory(api, {
      idempotencyKey: randomUUID(),
      name: `Persona groceries ${suffix}`,
      transactionType: "EXPENSE",
    });
    expect(categoryResult.isError).toBeFalsy();
    const category = JSON.parse(categoryResult.content[0].text) as { id: number };

    const budgetLimitByCurrency: Record<(typeof currencies)[number], number> = {
      EUR: 500,
      USD: 500,
      VND: 15_000_000,
    };
    for (const currency of currencies) {
      const result = await createBudget(api, {
        idempotencyKey: randomUUID(),
        categoryId: category.id,
        period: "MONTHLY",
        amountLimit: budgetLimitByCurrency[currency],
        startDate: "2026-01-01",
        currency,
      });
      expect(result.isError).toBeFalsy();
    }

    // Three months of expense history per currency, entered in one batch call each --
    // the workflow the batch tool exists for, rather than one create_transaction per row.
    const months = ["2026-01", "2026-02", "2026-03"];
    const perCurrencyAmount: Record<(typeof currencies)[number], number> = {
      EUR: 40,
      USD: 35,
      VND: 900_000,
    };
    const createdTransactionIdsByCurrency: Record<(typeof currencies)[number], number[]> = {} as never;
    for (const currency of currencies) {
      const batchResult = await createTransactionsBatch(api, {
        idempotencyKey: randomUUID(),
        transactions: months.map((month, index) => ({
          clientRequestId: `${currency}-${month}-batch-row-${index}`,
          transaction: {
            transactionType: "EXPENSE",
            amount: perCurrencyAmount[currency],
            transactionDate: `${month}-10`,
            accountId: accountsByCurrency[currency].id,
            categoryId: category.id,
          },
        })),
      });
      expect(batchResult.isError).toBeFalsy();
      const batch = JSON.parse(batchResult.content[0].text) as {
        results: Array<{ status: string; transaction: { id: number } | null }>;
      };
      expect(batch.results).toHaveLength(3);
      expect(batch.results.every((r) => r.status === "CREATED")).toBe(true);
      createdTransactionIdsByCurrency[currency] = batch.results.map((r) => r.transaction!.id);
    }

    // Correct one miskeyed entry, the way an agent fixes a mistake it notices after entry.
    const [firstEurTransactionId] = createdTransactionIdsByCurrency.EUR;
    const correctionResult = await api.put(`/transactions/${firstEurTransactionId}`, { amount: 55 });
    expect(correctionResult.status).toBe(200);

    for (const currency of currencies) {
      const historyResult = await getBudgetHistory(api, {
        from: "2026-01-01",
        to: "2026-03-31",
        currency,
      });
      expect(historyResult.isError).toBeFalsy();
      const history = JSON.parse(historyResult.content[0].text) as Array<{
        currency: string;
        periodStart: string;
        spent: number;
      }>;
      expect(history).toHaveLength(3);
      const expectedTotalSpend =
        currency === "EUR" ? 55 + perCurrencyAmount.EUR * 2 : perCurrencyAmount[currency] * 3;
      const totalSpend = history.reduce((sum, period) => sum + period.spent, 0);
      expect(totalSpend).toBe(expectedTotalSpend);
      expect(history.every((period) => period.currency === currency)).toBe(true);
    }
  });

  it("creates a cross-currency TRANSFER with destinationAmount, and rejects one missing it", async () => {
    const suffix = Date.now();
    const email = `mcp-xfer-${suffix}@test.com`;
    const backend = axios.create({ baseURL: `${BACKEND_URL}/api/v1` });

    const registerResponse = await unwrapAxiosErrors(() =>
      backend.post("/auth/register", {
        email,
        password: "pass1234",
        firstName: "MCP",
        lastName: "Transfer",
      })
    );
    const jwt: string = registerResponse.data.accessToken;

    const tokenResponse = await unwrapAxiosErrors(() =>
      backend.post(
        "/tokens",
        { name: "MCP Transfer Token", scope: "WRITE", expiryDays: 365 },
        { headers: { Authorization: `Bearer ${jwt}` } }
      )
    );
    const pat: string = tokenResponse.data.plaintextToken;
    const api = createApiClient({ apiUrl: BACKEND_URL!, apiToken: pat });

    const eurAccountResult = await createAccount(api, {
      idempotencyKey: randomUUID(),
      name: `Transfer EUR account ${suffix}`,
      accountType: "BANK",
      currency: "EUR",
      initialBalance: 1_000,
    });
    expect(eurAccountResult.isError).toBeFalsy();
    const eurAccount = JSON.parse(eurAccountResult.content[0].text) as { id: number };

    const vndAccountResult = await createAccount(api, {
      idempotencyKey: randomUUID(),
      name: `Transfer VND account ${suffix}`,
      accountType: "BANK",
      currency: "VND",
      initialBalance: 0,
    });
    expect(vndAccountResult.isError).toBeFalsy();
    const vndAccount = JSON.parse(vndAccountResult.content[0].text) as { id: number };

    // mapApiError deliberately returns a generic message for 4xx (never the response body,
    // which could carry sensitive details) — so the retry guidance for this field lives in
    // create_transaction's static tool description, not in the error text itself.
    const missingDestination = await createTransaction(api, {
      idempotencyKey: randomUUID(),
      transactionType: "TRANSFER",
      amount: 500,
      transactionDate: "2026-06-01",
      accountId: eurAccount.id,
      transferAccountId: vndAccount.id,
    });
    expect(missingDestination.isError).toBe(true);
    expect(missingDestination.content[0].text).toContain("400");

    const created = await createTransaction(api, {
      idempotencyKey: randomUUID(),
      transactionType: "TRANSFER",
      amount: 500,
      destinationAmount: 14_600_000,
      transactionDate: "2026-06-01",
      accountId: eurAccount.id,
      transferAccountId: vndAccount.id,
    });
    expect(created.isError).toBeFalsy();
    const transfer = JSON.parse(created.content[0].text) as { destinationAmount: number };
    expect(transfer.destinationAmount).toBe(14_600_000);
  });

  /**
   * Task 8.5: proves caller-controlled replay actually works end to end against the real
   * backend, not just that the MCP layer forwards a header. Same tool, same idempotencyKey,
   * same payload, called twice — must create exactly one transaction and apply its balance
   * effect exactly once.
   */
  it("replays create_transaction safely when the same idempotencyKey and payload are resubmitted", async () => {
    const suffix = Date.now();
    const email = `mcp-replay-${suffix}@test.com`;
    const backend = axios.create({ baseURL: `${BACKEND_URL}/api/v1` });

    const registerResponse = await unwrapAxiosErrors(() =>
      backend.post("/auth/register", {
        email,
        password: "pass1234",
        firstName: "MCP",
        lastName: "Replay",
      })
    );
    const jwt: string = registerResponse.data.accessToken;

    const tokenResponse = await unwrapAxiosErrors(() =>
      backend.post(
        "/tokens",
        { name: "MCP Replay Token", scope: "WRITE", expiryDays: 30 },
        { headers: { Authorization: `Bearer ${jwt}` } }
      )
    );
    const pat: string = tokenResponse.data.plaintextToken;
    const api = createApiClient({ apiUrl: BACKEND_URL!, apiToken: pat });

    const accountResult = await createAccount(api, {
      idempotencyKey: randomUUID(),
      name: `Replay account ${suffix}`,
      accountType: "BANK",
      currency: "EUR",
      initialBalance: 1_000,
    });
    expect(accountResult.isError).toBeFalsy();
    const account = JSON.parse(accountResult.content[0].text) as { id: number };

    const idempotencyKey = randomUUID();
    const transactionPayload = {
      idempotencyKey,
      transactionType: "EXPENSE" as const,
      amount: 40,
      transactionDate: "2026-04-01",
      accountId: account.id,
    };

    const first = await createTransaction(api, transactionPayload);
    expect(first.isError).toBeFalsy();
    const second = await createTransaction(api, transactionPayload);
    expect(second.isError).toBeFalsy();

    const firstTransaction = JSON.parse(first.content[0].text) as { id: number };
    const secondTransaction = JSON.parse(second.content[0].text) as { id: number };
    expect(secondTransaction.id).toBe(firstTransaction.id);

    const listResult = await unwrapAxiosErrors(() =>
      api.get(`/transactions?accountId=${account.id}&size=100`)
    );
    const page = listResult.data as { content: Array<{ id: number }> };
    expect(page.content.filter((t) => t.id === firstTransaction.id)).toHaveLength(1);

    const accountsResult = await unwrapAxiosErrors(() => api.get("/accounts"));
    const accounts = accountsResult.data as Array<{ id: number; currentBalance: number }>;
    const refreshedAccount = accounts.find((a) => a.id === account.id)!;
    expect(refreshedAccount.currentBalance).toBe(1_000 - 40);
  });

  /**
   * Task 8.5 batch equivalent: same batch-level idempotencyKey and same row clientRequestIds
   * resubmitted must produce exactly one set of transactions and one balance effect, not a
   * duplicate per retried call.
   */
  it("replays create_transactions_batch safely when the same batch key and rows are resubmitted", async () => {
    const suffix = Date.now();
    const email = `mcp-batch-replay-${suffix}@test.com`;
    const backend = axios.create({ baseURL: `${BACKEND_URL}/api/v1` });

    const registerResponse = await unwrapAxiosErrors(() =>
      backend.post("/auth/register", {
        email,
        password: "pass1234",
        firstName: "MCP",
        lastName: "BatchReplay",
      })
    );
    const jwt: string = registerResponse.data.accessToken;

    const tokenResponse = await unwrapAxiosErrors(() =>
      backend.post(
        "/tokens",
        { name: "MCP Batch Replay Token", scope: "WRITE", expiryDays: 30 },
        { headers: { Authorization: `Bearer ${jwt}` } }
      )
    );
    const pat: string = tokenResponse.data.plaintextToken;
    const api = createApiClient({ apiUrl: BACKEND_URL!, apiToken: pat });

    const accountResult = await createAccount(api, {
      idempotencyKey: randomUUID(),
      name: `Batch replay account ${suffix}`,
      accountType: "BANK",
      currency: "EUR",
      initialBalance: 1_000,
    });
    expect(accountResult.isError).toBeFalsy();
    const account = JSON.parse(accountResult.content[0].text) as { id: number };

    const batchIdempotencyKey = randomUUID();
    const batchPayload = {
      idempotencyKey: batchIdempotencyKey,
      transactions: [
        {
          clientRequestId: `batch-replay-row-0-${suffix}`,
          transaction: {
            transactionType: "EXPENSE" as const,
            amount: 15,
            transactionDate: "2026-04-01",
            accountId: account.id,
          },
        },
        {
          clientRequestId: `batch-replay-row-1-${suffix}`,
          transaction: {
            transactionType: "EXPENSE" as const,
            amount: 25,
            transactionDate: "2026-04-02",
            accountId: account.id,
          },
        },
      ],
    };

    const first = await createTransactionsBatch(api, batchPayload);
    expect(first.isError).toBeFalsy();
    const second = await createTransactionsBatch(api, batchPayload);
    expect(second.isError).toBeFalsy();

    const firstBatch = JSON.parse(first.content[0].text) as {
      results: Array<{ status: string; transaction: { id: number } | null }>;
    };
    const secondBatch = JSON.parse(second.content[0].text) as {
      results: Array<{ status: string; transaction: { id: number } | null }>;
    };
    expect(firstBatch.results.every((r) => r.status === "CREATED")).toBe(true);
    const firstIds = firstBatch.results.map((r) => r.transaction!.id).sort();
    const secondIds = secondBatch.results.map((r) => r.transaction!.id).sort();
    expect(secondIds).toEqual(firstIds);

    const listResult = await unwrapAxiosErrors(() =>
      api.get(`/transactions?accountId=${account.id}&size=100`)
    );
    const page = listResult.data as { content: Array<{ id: number }> };
    for (const id of firstIds) {
      expect(page.content.filter((t) => t.id === id)).toHaveLength(1);
    }

    const accountsResult = await unwrapAxiosErrors(() => api.get("/accounts"));
    const accounts = accountsResult.data as Array<{ id: number; currentBalance: number }>;
    const refreshedAccount = accounts.find((a) => a.id === account.id)!;
    expect(refreshedAccount.currentBalance).toBe(1_000 - 15 - 25);
  });
});
