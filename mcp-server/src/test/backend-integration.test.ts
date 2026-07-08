import axios from "axios";
import { describe, expect, it } from "vitest";
import { createApiClient } from "../api-client.js";
import { listAccounts } from "../tools/accounts.js";

const BACKEND_URL = process.env.FINTRACK_TEST_BACKEND_URL;

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

    const registerResponse = await backend.post("/auth/register", {
      email,
      password: "pass1234",
      firstName: "MCP",
      lastName: "Integration",
    });
    const jwt: string = registerResponse.data.accessToken;

    const accountResponse = await backend.post(
      "/accounts",
      { name: "MCP Test Account", accountType: "BANK", currency: "USD", initialBalance: "250.00" },
      { headers: { Authorization: `Bearer ${jwt}` } }
    );
    const accountId: number = accountResponse.data.id;

    const tokenResponse = await backend.post(
      "/tokens",
      { name: "MCP Integration Test Token", scope: "READ", expiryDays: 1 },
      { headers: { Authorization: `Bearer ${jwt}` } }
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
});
