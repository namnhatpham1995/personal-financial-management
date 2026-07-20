import { isAxiosError, type AxiosError } from "axios";

export type IdempotencyErrorCode = "idempotency_key_conflict" | "operation_in_progress";

/**
 * Extracts the typed idempotency-conflict error code from a 409 AxiosError, if present.
 * Backend shape: { error: { code: "idempotency_key_conflict" | "operation_in_progress", ... } }
 * Returns null for any other error (wrong status, non-Axios error, unrecognized code).
 */
export function getIdempotencyErrorCode(err: unknown): IdempotencyErrorCode | null {
  if (!isAxiosError(err) || err.response?.status !== 409) return null;
  const code = extractErrorCode(err);
  if (code === "idempotency_key_conflict" || code === "operation_in_progress") return code;
  return null;
}

function extractErrorCode(err: AxiosError): string | undefined {
  const data = err.response?.data as { error?: { code?: string }; code?: string } | undefined;
  return data?.error?.code ?? data?.code;
}

/** Retry-After header (seconds), when the server sent one on an operation_in_progress 409. */
export function getRetryAfterSeconds(err: unknown): number | null {
  if (!isAxiosError(err)) return null;
  const header = err.response?.headers?.["retry-after"];
  if (!header) return null;
  const seconds = Number(header);
  return Number.isFinite(seconds) ? seconds : null;
}
