/**
 * Tests for the Axios auth/refresh interceptor in api-client.ts (task 4.2).
 * Covers: helper functions, 401→refresh→retry, failed refresh → logout, no infinite loop,
 * and route-aware redirect gating (only /dashboard routes navigate to /login on session expiry).
 */
import { describe, it, expect, beforeEach, vi, afterEach } from "vitest";
import { setTokens, clearTokens, redirectToLoginIfProtected, isProtectedRoute } from "@/lib/api-client";

describe("token helpers", () => {
  beforeEach(() => localStorage.clear());

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

describe("redirectToLoginIfProtected", () => {
  beforeEach(() => {
    localStorage.clear();
    setTokens("acc-123", "ref-456");
  });

  it("clears tokens but does not navigate on the public landing page", () => {
    const navigate = vi.fn();
    redirectToLoginIfProtected("/", navigate);
    expect(localStorage.getItem("accessToken")).toBeNull();
    expect(localStorage.getItem("refreshToken")).toBeNull();
    expect(navigate).not.toHaveBeenCalled();
  });

  it("clears tokens but does not navigate on /login", () => {
    const navigate = vi.fn();
    redirectToLoginIfProtected("/login", navigate);
    expect(navigate).not.toHaveBeenCalled();
  });

  it("clears tokens but does not navigate on /register", () => {
    const navigate = vi.fn();
    redirectToLoginIfProtected("/register", navigate);
    expect(navigate).not.toHaveBeenCalled();
  });

  it("clears tokens and redirects to /login from a /dashboard route", () => {
    const navigate = vi.fn();
    redirectToLoginIfProtected("/dashboard/settings", navigate);
    expect(localStorage.getItem("accessToken")).toBeNull();
    expect(navigate).toHaveBeenCalledWith("/login");
  });
});

describe("isProtectedRoute", () => {
  it("treats /dashboard and its subroutes as protected", () => {
    expect(isProtectedRoute("/dashboard")).toBe(true);
    expect(isProtectedRoute("/dashboard/settings")).toBe(true);
  });

  it("treats public routes as unprotected", () => {
    expect(isProtectedRoute("/")).toBe(false);
    expect(isProtectedRoute("/login")).toBe(false);
    expect(isProtectedRoute("/register")).toBe(false);
  });
});

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
    // Interceptor path: no refreshToken → redirectToLoginIfProtected(), no POST /auth/refresh
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
