import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import axios from "axios";
import { describe, expect, it } from "vitest";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const SERVER_ENTRY = path.resolve(__dirname, "../../dist/index.js");

const BACKEND_URL = process.env.FINTRACK_TEST_BACKEND_URL;

/**
 * Verifies the full chain no other test touches: MCP stdio transport ->
 * config parsing -> PAT header injection -> real backend -> tool-result
 * shaping. Every other mcp-server test mocks the axios client; this one
 * spawns the built server and drives it through the real MCP SDK client.
 *
 * Requires a running Fintrack backend at FINTRACK_TEST_BACKEND_URL and a
 * built server (`npm run build`). Skipped locally when the env var is unset
 * so the default `npm test` stays fast and offline-safe; the CI
 * `mcp-integration` job sets it after provisioning the backend.
 */
describe.skipIf(!BACKEND_URL)("MCP server <-> real backend", () => {
  it("list_accounts returns real backend data through the stdio transport", async () => {
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

    const transport = new StdioClientTransport({
      command: process.execPath,
      args: [SERVER_ENTRY],
      env: { FINTRACK_API_URL: BACKEND_URL!, FINTRACK_API_TOKEN: pat },
      stderr: "pipe",
    });
    transport.onerror = (err) => console.error("[mcp transport error]", err);
    transport.stderr?.on("data", (chunk) => console.error("[mcp server stderr]", chunk.toString()));

    const client = new Client({ name: "mcp-integration-test-client", version: "0.0.1" });
    let connected = false;

    try {
      await client.connect(transport);
      connected = true;

      const result = await client.callTool({ name: "list_accounts", arguments: {} });
      const content = result.content as Array<{ type: string; text: string }>;
      expect(content).toHaveLength(1);
      expect(content[0].type).toBe("text");

      const accounts = JSON.parse(content[0].text);
      expect(Array.isArray(accounts)).toBe(true);
      const created = accounts.find((a: { id: number }) => a.id === accountId);
      expect(created).toBeDefined();
      expect(created.name).toBe("MCP Test Account");
      expect(created.currency).toBe("USD");

      // The tool result must never contain the PAT or the JWT
      expect(content[0].text).not.toContain(pat);
      expect(content[0].text).not.toContain(jwt);
    } finally {
      // A close() failure (e.g. the connection never succeeded) must never mask
      // the real assertion/connection error that's already propagating.
      if (connected) {
        await client.close().catch((err) => console.error("[mcp client close error]", err));
      }
    }
  });
});
