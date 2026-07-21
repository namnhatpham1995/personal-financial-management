import axios, { type AxiosInstance, isAxiosError } from "axios";
import type { Config } from "./config.js";

/**
 * "definitive" — the backend received the request and rejected it outright (bad input, auth,
 * not-found, payload conflict); retrying with a new key or edited input is the right move.
 * "unknown" — no confirmed response reached the client (network/timeout) or the backend may
 * have partially committed before failing (5xx); the caller cannot tell whether the mutation
 * took effect, so guidance must point at retrying the SAME logical operation with the SAME
 * idempotency key (or inspecting current state), never at generating a new key.
 */
export type ApiErrorOutcome = "definitive" | "unknown";

export class FintrackApiError extends Error {
  readonly outcome: ApiErrorOutcome;

  constructor(message: string, outcome: ApiErrorOutcome = "definitive") {
    super(message);
    this.name = "FintrackApiError";
    this.outcome = outcome;
  }
}

export function createApiClient(config: Config): AxiosInstance {
  return axios.create({
    baseURL: `${config.apiUrl}/api/v1`,
    headers: { Authorization: `Bearer ${config.apiToken}` },
    timeout: 15_000,
  });
}

/**
 * Guidance for an ambiguous/unknown commit outcome — the caller must retry the same logical
 * operation with the same idempotency key (or inspect current state), never generate a new key,
 * since a new key would defeat the caller-controlled replay protection the key exists for.
 */
const UNKNOWN_OUTCOME_GUIDANCE =
  "The commit outcome is unknown — the request may or may not have taken effect. " +
  "Inspect current state, or retry the exact same operation with the exact same idempotencyKey " +
  "(do not generate a new key).";

/**
 * Maps any API/network failure to a short, credential-safe message — never the token,
 * request headers, response body, or a stack trace, any of which could leak the PAT or
 * internal details to the MCP client's transcript.
 */
export function mapApiError(error: unknown): FintrackApiError {
  if (isAxiosError(error)) {
    const status = error.response?.status;
    if (status === 401) {
      return new FintrackApiError(
        "The API token is invalid, expired, or revoked. Create a new one from Settings > API Tokens.",
        "definitive"
      );
    }
    if (status === 403) {
      return new FintrackApiError("The API token's scope does not permit this operation.", "definitive");
    }
    if (status === 429) {
      const bodyRetry = Number(error.response?.data?.retryAfterSeconds);
      const headerRetry = Number(error.response?.headers?.["retry-after"]);
      const retryAfter = Number.isFinite(bodyRetry) && bodyRetry > 0 ? bodyRetry : headerRetry;
      return new FintrackApiError(
        Number.isFinite(retryAfter) && retryAfter > 0
          ? `Rate limit exceeded for this token. Retry after ${Math.ceil(retryAfter)} seconds.`
          : "Rate limit exceeded for this token. Slow down and try again shortly.",
        "definitive"
      );
    }
    // Any other 4xx (400 validation, 404 not found, 409 conflict — including an
    // idempotency-key payload conflict) means the backend received and definitively rejected
    // the request; this is not a commit-uncertainty case.
    if (status !== undefined && status >= 400 && status < 500) {
      return new FintrackApiError(`Request rejected by the Fintrack API (${status}).`, "definitive");
    }
    // A 5xx means the backend may have partially committed the mutation before failing, and no
    // request at all reaching a response (network error/timeout) means the outcome is equally
    // unknown — both are "unknown", never "definitive".
    if (status !== undefined && status >= 500) {
      return new FintrackApiError(
        `The Fintrack API is currently unavailable. ${UNKNOWN_OUTCOME_GUIDANCE}`,
        "unknown"
      );
    }
    return new FintrackApiError(
      `Could not reach the Fintrack API. Check FINTRACK_API_URL. ${UNKNOWN_OUTCOME_GUIDANCE}`,
      "unknown"
    );
  }
  return new FintrackApiError("An unexpected error occurred while calling the Fintrack API.", "unknown");
}
