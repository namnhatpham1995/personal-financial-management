export interface Config {
  backendApiUrl: string;
  llmProvider: "anthropic" | "stub";
  anthropicApiKey: string | undefined;
  anthropicModel: string;
  checkpointerDbUrl: string;
  port: number;
}

/**
 * Fails fast at startup on missing/malformed configuration — mirrors mcp-server/src/config.ts.
 * ANTHROPIC_API_KEY is only required when LLM_PROVIDER=anthropic; CI and local dev default to
 * the stub provider so no job ever needs a live model key (design.md D7).
 */
export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const backendApiUrl = env.BACKEND_API_URL;
  if (!backendApiUrl) {
    throw new Error(
      "BACKEND_API_URL is not set. Point it at the Fintrack backend, e.g. http://localhost:8080"
    );
  }

  const llmProvider = env.LLM_PROVIDER ?? "stub";
  if (llmProvider !== "anthropic" && llmProvider !== "stub") {
    throw new Error(`LLM_PROVIDER must be "anthropic" or "stub", got "${llmProvider}".`);
  }

  const anthropicApiKey = env.ANTHROPIC_API_KEY;
  if (llmProvider === "anthropic" && !anthropicApiKey) {
    throw new Error("ANTHROPIC_API_KEY is not set but LLM_PROVIDER=anthropic.");
  }

  const checkpointerDbUrl = env.CHECKPOINTER_DB_URL;
  if (!checkpointerDbUrl) {
    throw new Error(
      "CHECKPOINTER_DB_URL is not set. Point it at the same PostgreSQL instance the backend uses (a dedicated schema)."
    );
  }

  const port = env.PORT ? Number(env.PORT) : 8081;
  if (!Number.isFinite(port) || port <= 0) {
    throw new Error(`PORT must be a positive number, got "${env.PORT}".`);
  }

  return {
    backendApiUrl: backendApiUrl.replace(/\/+$/, ""),
    llmProvider,
    anthropicApiKey,
    anthropicModel: env.ANTHROPIC_MODEL ?? "claude-sonnet-4-5",
    checkpointerDbUrl,
    port,
  };
}

export function loadConfigOrExit(env: NodeJS.ProcessEnv = process.env): Config {
  try {
    return loadConfig(env);
  } catch (err) {
    // eslint-disable-next-line no-console
    console.error(err instanceof Error ? err.message : "Invalid agent-service configuration");
    process.exit(1);
  }
}
