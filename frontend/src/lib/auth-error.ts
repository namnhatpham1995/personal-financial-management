import { isAxiosError } from "axios";

export type AuthFailureKind =
  | "credentials"
  | "rate_limit"
  | "connection"
  | "validation"
  | "server";

export interface AuthFailure {
  kind: AuthFailureKind;
}

type AuthFlow = "login" | "register";

/**
 * Classifies an auth request failure into a kind. This module can't call
 * useTranslations (not a component) — call sites resolve the display message
 * via t(`errors.${kind}`) in the "auth" namespace.
 */
export function classifyAuthError(error: unknown, flow: AuthFlow): AuthFailure {
  if (!isAxiosError(error)) {
    return { kind: "server" };
  }

  const status = error.response?.status;
  if (status === 429) {
    return { kind: "rate_limit" };
  }
  if (status === undefined) {
    return { kind: "connection" };
  }
  if (status >= 500) {
    return { kind: "server" };
  }
  if (flow === "login") {
    return { kind: "credentials" };
  }
  if (status === 400 || status === 409 || status === 422) {
    return { kind: "validation" };
  }
  return { kind: "server" };
}
