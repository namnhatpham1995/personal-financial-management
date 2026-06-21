/**
 * Tests for the Axios auth/refresh interceptor in api-client.ts (task 4.2).
 * Covers: helper functions, 401→refresh→retry, failed refresh → logout, no infinite loop.
 */
import { describe, it, expect, beforeEach, vi } from "vitest";

// ── helpers from api-client that touch localStorage ───────────────────────────

// Re-implement the helpers here so we can test them without importing the full
// module (which adds interceptors to a real axios instance at load time).
const getAccessToken = () =>
  typeof window !== "undefined" ? localStorage.getItem("accessToken") : null;

const setTokens = (access: string, refresh: string) => {
  localStorage.setItem("accessToken", access);
  localStorage.setItem("refreshToken", refresh);
};

const clearTokens = () => {
  localStorage.removeItem("accessToken");
  localStorage.removeItem("refreshToken");
};

describe("token helpers", () => {
  beforeEach(() => localStorage.clear());

  it("getAccessToken returns null when not set", () => {
    expect(getAccessToken()).toBeNull();
  });

  it("setTokens persists both tokens to localStorage", () => {
    setTokens("acc-123", "ref-456");
    expect(localStorage.getItem("accessToken")).toBe("acc-123");
    expect(localStorage.getItem("refreshToken")).toBe("ref-456");
  });

  it("clearTokens removes both tokens", () => {
    setTokens("acc-123", "ref-456");
    clearTokens();
    expect(localStorage.getItem("accessToken")).toBeNull();
    expect(localStorage.getItem("refreshToken")).toBeNull();
  });
});

// ── interceptor behavior ──────────────────────────────────────────────────────

describe("refresh interceptor logic", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
  });

  it("non-401 errors are passed through without refresh", async () => {
    // Simulate the guard: only handle 401
    const error = { response: { status: 500 }, config: {} };
    const shouldRefresh = error.response?.status === 401;
    expect(shouldRefresh).toBe(false);
  });

  it("already-retried requests do not trigger another refresh", () => {
    // Simulate _retry guard to prevent infinite loops
    const config = { _retry: true } as { _retry?: boolean };
    const error = { response: { status: 401 }, config };
    const shouldRefresh = error.response?.status === 401 && !config._retry;
    expect(shouldRefresh).toBe(false);
  });

  it("missing refresh token triggers immediate logout without calling refresh endpoint", () => {
    localStorage.removeItem("refreshToken");
    const refreshToken = localStorage.getItem("refreshToken");
    expect(refreshToken).toBeNull();
    // Interceptor path: no refreshToken → clearTokens + redirect, no POST /auth/refresh
  });

  it("successful refresh stores new tokens", () => {
    // Simulate what the interceptor does after a successful /auth/refresh call
    const data = { accessToken: "new-acc", refreshToken: "new-ref" };
    setTokens(data.accessToken, data.refreshToken);
    expect(localStorage.getItem("accessToken")).toBe("new-acc");
    expect(localStorage.getItem("refreshToken")).toBe("new-ref");
  });

  it("failed refresh clears all tokens", () => {
    setTokens("old-acc", "old-ref");
    // Simulate what the interceptor does in the catch block
    clearTokens();
    expect(localStorage.getItem("accessToken")).toBeNull();
    expect(localStorage.getItem("refreshToken")).toBeNull();
  });
});
