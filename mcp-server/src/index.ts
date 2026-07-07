#!/usr/bin/env node
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { loadConfig, type Config } from "./config.js";
import { createApiClient } from "./api-client.js";
import { registerAccountTools } from "./tools/accounts.js";
import { registerTransactionTools } from "./tools/transactions.js";
import { registerBudgetTools } from "./tools/budgets.js";
import { registerAnalyticsTools } from "./tools/analytics.js";

function loadConfigOrExit(): Config {
  try {
    return loadConfig();
  } catch (err) {
    // Startup config errors never echo the token value — only whether it's present/well-formed.
    console.error(`fintrack-mcp-server: configuration error — ${(err as Error).message}`);
    return process.exit(1);
  }
}

async function main(): Promise<void> {
  const config = loadConfigOrExit();
  const apiClient = createApiClient(config);

  const server = new McpServer({ name: "fintrack-mcp-server", version: "0.1.0" });

  registerAccountTools(server, apiClient);
  registerTransactionTools(server, apiClient);
  registerBudgetTools(server, apiClient);
  registerAnalyticsTools(server, apiClient);

  const transport = new StdioServerTransport();
  await server.connect(transport);
}

main().catch((err: unknown) => {
  console.error(`fintrack-mcp-server: fatal error — ${(err as Error).message}`);
  process.exit(1);
});
