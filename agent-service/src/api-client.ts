import axios, { type AxiosInstance, isAxiosError } from "axios";
import type { Config } from "./config.js";

export class BackendApiError extends Error {
  /** True when the failure is transient (network/5xx) and a retry might succeed. */
  readonly retryable: boolean;

  constructor(message: string, retryable: boolean) {
    super(message);
    this.name = "BackendApiError";
    this.retryable = retryable;
  }
}

/**
 * Creates a client scoped to a single run's agent token — mirrors mcp-server/src/api-client.ts,
 * but the bearer is the short-lived per-run token minted by the backend at run start rather
 * than a long-lived PAT.
 */
export function createApiClient(config: Config, agentToken: string): AxiosInstance {
  return axios.create({
    baseURL: `${config.backendApiUrl}/api/v1`,
    headers: { Authorization: `Bearer ${agentToken}` },
    timeout: 30_000,
  });
}

/**
 * Maps any API/network failure to a short, credential-safe message — never the token, request
 * headers, response body, or a stack trace. Distinguishes retryable (network/5xx/expired-token)
 * from non-retryable (4xx business rejection) so callers can report the run's failure correctly.
 */
export function mapApiError(error: unknown): BackendApiError {
  if (isAxiosError(error)) {
    const status = error.response?.status;
    if (status === 401) {
      return new BackendApiError("Agent token is invalid or expired.", true);
    }
    if (status === 403) {
      return new BackendApiError("Agent token scope does not permit this operation.", false);
    }
    if (status === 409) {
      return new BackendApiError("Run is no longer in the expected state.", false);
    }
    if (status !== undefined && status >= 400 && status < 500) {
      return new BackendApiError(`Backend rejected the request (${status}).`, false);
    }
    if (status !== undefined && status >= 500) {
      return new BackendApiError("Backend is currently unavailable.", true);
    }
    return new BackendApiError("Could not reach the backend. Check BACKEND_API_URL.", true);
  }
  return new BackendApiError("An unexpected error occurred while calling the backend.", true);
}
