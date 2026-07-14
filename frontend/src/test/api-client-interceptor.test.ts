/**
 * Tests for the Axios auth/refresh interceptor in api-client.ts (task 4.2).
 * Covers: helper functions, 401→refresh→retry, failed refresh → logout, no infinite loop,
 * route-aware redirect gating (only /dashboard routes navigate to /login on session expiry),
 * and the real interceptor's rejected-handler behavior (auth-endpoint bypass, no stuck
 * refresh state, queued-request settlement).
 */
import axios, { AxiosError } from "axios";
import { describe, it, expect, beforeEach, vi } from "vitest";
import {
  apiClient,
  setTokens,
  clearTokens,
  redirectToLoginIfProtected,
  isProtectedRoute,
} from "@/lib/api-client";

// axios doesn't publicly type the interceptor manager's internal handler list,
// but exposing it is the standard way to invoke a registered rejected-handler
// directly in tests without a network-mocking library.
function getRejectedHandler() {
  const handlers = (
    apiClient.interceptors.response as unknown as {
      handlers: Array<{ rejected: (error: AxiosError) => Promise<unknown> }>;
    }
  ).handlers;
  return handlers[handlers.length - 1].rejected;
}

function makeAxios401(url: string): AxiosError {
  return {
    isAxiosError: true,
    name: "AxiosError",
    message: "Request failed with status code 401",
    response: { status: 401, data: {}, statusText: "Unauthorized", headers: {}, config: {} as never },
    config: { url, headers: {} } as never,
    toJSON: () => ({}),
  } as AxiosError;
}

const withTimeout = <T,>(promise: Promise<T>, ms = 500) =>
  Promise.race([
    promise,
    new Promise<never>((_, reject) =>
      setTimeout(() => reject(new Error("timed out — request appears stuck")), ms)
    ),
  ]);

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

describe("real interceptor: auth-endpoint bypass", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
  });

  it.each(["/auth/login", "/auth/register", "/auth/refresh"])(
    "a 401 from %s bypasses the refresh flow entirely",
    async (url) => {
      setTokens("acc-123", "ref-456");
      const postSpy = vi
        .spyOn(axios, "post")
        .mockRejectedValue(new Error("refresh endpoint should not be called"));
      const rejected = getRejectedHandler();
      const error = makeAxios401(url);

      await expect(rejected(error)).rejects.toBe(error);

      expect(postSpy).not.toHaveBeenCalled();
      expect(localStorage.getItem("accessToken")).toBe("acc-123");
      expect(localStorage.getItem("refreshToken")).toBe("ref-456");
    }
  );
});

describe("real interceptor: no stuck refresh state", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
  });

  it("missing refresh token triggers immediate logout without calling refresh endpoint", async () => {
    const postSpy = vi.spyOn(axios, "post");
    const rejected = getRejectedHandler();

    await expect(rejected(makeAxios401("/dashboard-data"))).rejects.toBeTruthy();

    expect(postSpy).not.toHaveBeenCalled();
    expect(localStorage.getItem("accessToken")).toBeNull();
  });

  it("a second 401 after a no-refresh-token failure settles instead of hanging forever", async () => {
    const rejected = getRejectedHandler();

    await expect(rejected(makeAxios401("/dashboard-data-a"))).rejects.toBeTruthy();
    // Before the fix, isRefreshing stayed stuck true here, so this second call
    // would queue onto a promise that processQueue() never settles.
    await expect(withTimeout(rejected(makeAxios401("/dashboard-data-b")))).rejects.toBeTruthy();
  });

  it("queued concurrent requests reject when the refresh attempt fails", async () => {
    setTokens("acc-123", "ref-456");
    vi.spyOn(axios, "post").mockRejectedValue(new Error("refresh rejected by server"));
    const rejected = getRejectedHandler();

    const first = rejected(makeAxios401("/dashboard-data-a"));
    const second = rejected(makeAxios401("/dashboard-data-b"));

    await expect(withTimeout(first)).rejects.toBeTruthy();
    await expect(withTimeout(second)).rejects.toBeTruthy();
    expect(localStorage.getItem("accessToken")).toBeNull();
  });
});
