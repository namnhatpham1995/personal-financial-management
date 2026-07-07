import axios, { type AxiosInstance, isAxiosError } from "axios";
import type { Config } from "./config.js";

export class FintrackApiError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "FintrackApiError";
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
 * Maps any API/network failure to a short, credential-safe message — never the token,
 * request headers, response body, or a stack trace, any of which could leak the PAT or
 * internal details to the MCP client's transcript.
 */
export function mapApiError(error: unknown): FintrackApiError {
  if (isAxiosError(error)) {
    const status = error.response?.status;
    if (status === 401) {
      return new FintrackApiError(
        "The API token is invalid, expired, or revoked. Create a new one from Settings > API Tokens."
      );
    }
    if (status === 403) {
      return new FintrackApiError("The API token's scope does not permit this operation.");
    }
    if (status === 429) {
      return new FintrackApiError("Rate limit exceeded for this token. Slow down and try again shortly.");
    }
    if (status !== undefined && status >= 400 && status < 500) {
      return new FintrackApiError(`Request rejected by the Fintrack API (${status}).`);
    }
    if (status !== undefined && status >= 500) {
      return new FintrackApiError("The Fintrack API is currently unavailable. Try again later.");
    }
    return new FintrackApiError("Could not reach the Fintrack API. Check FINTRACK_API_URL.");
  }
  return new FintrackApiError("An unexpected error occurred while calling the Fintrack API.");
}
