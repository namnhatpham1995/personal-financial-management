import { isAxiosError } from "axios";

export type AuthFailureKind =
  | "credentials"
  | "rate_limit"
  | "connection"
  | "validation"
  | "server";

export interface AuthFailure {
  kind: AuthFailureKind;
  message: string;
}

type AuthFlow = "login" | "register";

const FAILURES: Record<AuthFailureKind, AuthFailure> = {
  credentials: {
    kind: "credentials",
    message: "Invalid email or password.",
  },
  rate_limit: {
    kind: "rate_limit",
    message: "Too many attempts. Please try again later.",
  },
  connection: {
    kind: "connection",
    message: "Unable to connect to Fintrack. Check your connection and try again.",
  },
  validation: {
    kind: "validation",
    message: "Unable to create an account with these details.",
  },
  server: {
    kind: "server",
    message: "Fintrack is temporarily unavailable. Please try again later.",
  },
};

export function classifyAuthError(error: unknown, flow: AuthFlow): AuthFailure {
  if (!isAxiosError(error)) {
    return FAILURES.server;
  }

  const status = error.response?.status;
  if (status === 429) {
    return FAILURES.rate_limit;
  }
  if (status === undefined) {
    return FAILURES.connection;
  }
  if (status >= 500) {
    return FAILURES.server;
  }
  if (flow === "login") {
    return FAILURES.credentials;
  }
  if (status === 400 || status === 409 || status === 422) {
    return FAILURES.validation;
  }
  return FAILURES.server;
}
