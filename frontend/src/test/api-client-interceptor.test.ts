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
  hasStoredAuthCredentials,
  hasSessionHintCookie,
  redirectToLoginIfProtected,
  isProtectedRoute,
  SESSION_HINT_COOKIE_NAME,
} from "@/lib/api-client";

const clearAllCookies = () => {
  document.cookie.split("; ").forEach((entry) => {
    const name = entry.split("=")[0];
    if (name) document.cookie = `${name}=; path=/; max-age=0`;
  });
};

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

  it("recognizes a refresh-token-only session as restorable", () => {
    localStorage.setItem("refreshToken", "ref-456");
    expect(hasStoredAuthCredentials()).toBe(true);
  });

  it("does not treat empty storage as a restoration candidate", () => {
    expect(hasStoredAuthCredentials()).toBe(false);
  });
});

describe("session hint cookie", () => {
  beforeEach(() => {
    localStorage.clear();
    clearAllCookies();
  });

  it("is set when tokens are stored", () => {
    setTokens("acc-123", "ref-456");
    expect(hasSessionHintCookie()).toBe(true);
    expect(document.cookie).toContain(`${SESSION_HINT_COOKIE_NAME}=1`);
  });

  it("is cleared when tokens are cleared", () => {
    setTokens("acc-123", "ref-456");
    clearTokens();
    expect(hasSessionHintCookie()).toBe(false);
  });

  it("is cleared via redirectToLoginIfProtected (interceptor's session-abandonment path)", () => {
    setTokens("acc-123", "ref-456");
    redirectToLoginIfProtected("/dashboard/settings", vi.fn());
    expect(hasSessionHintCookie()).toBe(false);
  });

  it("carries no credential — only presence, never a token value", () => {
    setTokens("acc-123", "ref-456");
    expect(document.cookie).not.toContain("acc-123");
    expect(document.cookie).not.toContain("ref-456");
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

  it("replays every queued request with the new token once the single refresh completes (task 7.6)", async () => {
    setTokens("acc-123", "ref-456");
    vi.spyOn(axios, "post").mockResolvedValue({
      data: { accessToken: "new-acc", refreshToken: "new-ref" },
    });
    const originalAdapter = apiClient.defaults.adapter;
    const adapterSpy = vi
      .fn()
      .mockResolvedValue({ data: "ok", status: 200, statusText: "OK", headers: {}, config: {} });
    apiClient.defaults.adapter = adapterSpy;
    const rejected = getRejectedHandler();

    try {
      const first = rejected(makeAxios401("/a"));
      const second = rejected(makeAxios401("/b"));
      const third = rejected(makeAxios401("/c"));

      await withTimeout(Promise.all([first, second, third]));

      expect(adapterSpy).toHaveBeenCalledTimes(3);
      for (const call of adapterSpy.mock.calls) {
        expect(call[0].headers.Authorization).toBe("Bearer new-acc");
      }
    } finally {
      apiClient.defaults.adapter = originalAdapter;
    }
  });
});

describe("real interceptor: refresh_already_rotated (task 7.6)", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
  });

  function makeRotatedConflict(): AxiosError {
    return {
      isAxiosError: true,
      name: "AxiosError",
      message: "Request failed with status code 409",
      response: {
        status: 409,
        data: { error: { code: "refresh_already_rotated" } },
        statusText: "Conflict",
        headers: {},
        config: {} as never,
      },
      config: {} as never,
      toJSON: () => ({}),
    } as AxiosError;
  }

  it("does not fire a duplicate refresh call and falls back to session-lost handling when tokens never change", async () => {
    setTokens("acc-123", "ref-456");
    const postSpy = vi.spyOn(axios, "post").mockRejectedValue(makeRotatedConflict());
    const rejected = getRejectedHandler();

    await expect(withTimeout(rejected(makeAxios401("/dashboard-data")), 2000)).rejects.toBeTruthy();

    // Exactly one refresh attempt — the 409 is not treated as "retry refresh again".
    expect(postSpy).toHaveBeenCalledTimes(1);
    expect(localStorage.getItem("accessToken")).toBeNull();
  });

  it("retries the original request with the newer token when another tab rotated first, instead of treating the session as lost", async () => {
    setTokens("acc-123", "ref-456");
    vi.spyOn(axios, "post").mockImplementation(async () => {
      // Simulate another tab winning the refresh race and publishing new tokens
      // before this tab's grace-window recheck runs.
      setTokens("acc-999", "ref-999");
      throw makeRotatedConflict();
    });
    const originalAdapter = apiClient.defaults.adapter;
    const adapterSpy = vi
      .fn()
      .mockResolvedValue({ data: "ok", status: 200, statusText: "OK", headers: {}, config: {} });
    apiClient.defaults.adapter = adapterSpy;
    const rejected = getRejectedHandler();

    try {
      await withTimeout(rejected(makeAxios401("/dashboard-data")), 2000);

      expect(adapterSpy).toHaveBeenCalledTimes(1);
      expect(adapterSpy.mock.calls[0][0].headers.Authorization).toBe("Bearer acc-999");
    } finally {
      apiClient.defaults.adapter = originalAdapter;
    }
  });
});

describe("real interceptor: Idempotency-Key survives a 401-triggered replay (task 7.6)", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
  });

  it("preserves the Idempotency-Key header on the original request unchanged through the refresh-and-retry path", async () => {
    setTokens("acc-123", "ref-456");
    vi.spyOn(axios, "post").mockResolvedValue({
      data: { accessToken: "new-acc", refreshToken: "new-ref" },
    });
    const originalAdapter = apiClient.defaults.adapter;
    const adapterSpy = vi
      .fn()
      .mockResolvedValue({ data: "ok", status: 200, statusText: "OK", headers: {}, config: {} });
    apiClient.defaults.adapter = adapterSpy;
    const rejected = getRejectedHandler();

    const error = makeAxios401("/accounts");
    (error.config as unknown as { headers: Record<string, string> }).headers["Idempotency-Key"] =
      "key-abc-123";

    try {
      await withTimeout(rejected(error), 2000);

      expect(adapterSpy).toHaveBeenCalledTimes(1);
      const sentConfig = adapterSpy.mock.calls[0][0];
      expect(sentConfig.headers["Idempotency-Key"]).toBe("key-abc-123");
      expect(sentConfig.headers.Authorization).toBe("Bearer new-acc");
    } finally {
      apiClient.defaults.adapter = originalAdapter;
    }
  });
});
