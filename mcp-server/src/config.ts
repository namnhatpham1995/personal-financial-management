export interface Config {
  apiUrl: string;
  apiToken: string;
}

const TOKEN_PREFIX = "fintrack_pat_";

/**
 * Fails fast at startup on missing/malformed configuration. Never echoes the token value —
 * only whether it looks well-formed — so misconfiguration never leaks a secret to logs.
 */
export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const apiUrl = env.FINTRACK_API_URL;
  const apiToken = env.FINTRACK_API_TOKEN;

  if (!apiUrl) {
    throw new Error(
      "FINTRACK_API_URL is not set. Point it at your Fintrack backend, e.g. http://localhost:8080"
    );
  }
  if (!apiToken) {
    throw new Error(
      "FINTRACK_API_TOKEN is not set. Create one from Settings > API Tokens in Fintrack."
    );
  }
  if (!apiToken.startsWith(TOKEN_PREFIX)) {
    throw new Error(
      `FINTRACK_API_TOKEN does not look like a Fintrack personal access token (expected prefix "${TOKEN_PREFIX}").`
    );
  }

  return { apiUrl: apiUrl.replace(/\/+$/, ""), apiToken };
}
