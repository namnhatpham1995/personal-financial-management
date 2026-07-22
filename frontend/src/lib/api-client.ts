import axios, { AxiosError, InternalAxiosRequestConfig, isAxiosError } from "axios";
import {
  acquireRefreshLock,
  isRefreshLockFresh,
  readRefreshLock,
  releaseRefreshLock,
  waitForTokensFromOtherTab,
} from "@/lib/cross-tab-refresh-lock";

const BASE_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

export const apiClient = axios.create({
  baseURL: `${BASE_URL}/api/v1`,
  headers: { "Content-Type": "application/json" },
  withCredentials: false,
});

// ── Token helpers ──────────────────────────────────────────────────────────────

export const getAccessToken = () =>
  typeof window !== "undefined" ? localStorage.getItem("accessToken") : null;

export const hasStoredAuthCredentials = () =>
  typeof window !== "undefined" &&
  Boolean(localStorage.getItem("accessToken") || localStorage.getItem("refreshToken"));

export const setTokens = (access: string, refresh: string) => {
  localStorage.setItem("accessToken", access);
  localStorage.setItem("refreshToken", refresh);
};

export const clearTokens = () => {
  localStorage.removeItem("accessToken");
  localStorage.removeItem("refreshToken");
};

// Protected surface mirrors auth-guard.tsx, which is only mounted under /dashboard.
// Redirecting to /login from public pages (/, /login, /register) would force
// anonymous visitors with stale tokens off pages they're allowed to see.
export const isProtectedRoute = (pathname: string) => pathname.startsWith("/dashboard");

export const redirectToLoginIfProtected = (
  pathname: string = typeof window !== "undefined" ? window.location.pathname : "",
  navigate: (url: string) => void = (url) => {
    window.location.href = url;
  }
) => {
  clearTokens();
  if (isProtectedRoute(pathname)) navigate("/login");
};

// ── Request: attach Bearer token ───────────────────────────────────────────────

apiClient.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = getAccessToken();
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

// ── Response: refresh on 401 ──────────────────────────────────────────────────

let isRefreshing = false;
let queue: Array<{ resolve: (t: string) => void; reject: (e: unknown) => void }> = [];

const processQueue = (error: unknown, token: string | null) => {
  queue.forEach((p) => (error ? p.reject(error) : p.resolve(token!)));
  queue = [];
};

// A 401 here is a definitive outcome (bad credentials / invalid refresh token),
// not an expired-session signal — never route it through the refresh flow.
const AUTH_ENDPOINTS = ["/auth/login", "/auth/register", "/auth/refresh"];
const isAuthEndpoint = (url: string | undefined) =>
  !!url && AUTH_ENDPOINTS.some((endpoint) => url.endsWith(endpoint));

/** Backend's typed 409 for a concurrent-refresh race inside its grace window. */
const isRefreshAlreadyRotated = (err: unknown): boolean => {
  if (!isAxiosError(err) || err.response?.status !== 409) return false;
  const data = err.response.data as { error?: { code?: string }; code?: string } | undefined;
  return (data?.error?.code ?? data?.code) === "refresh_already_rotated";
};

const REFRESH_RACE_RECHECK_DELAY_MS = 300;

apiClient.interceptors.response.use(
  (r) => r,
  async (error: AxiosError) => {
    const original = error.config as InternalAxiosRequestConfig & { _retry?: boolean };

    if (error.response?.status !== 401 || original._retry || isAuthEndpoint(original.url)) {
      return Promise.reject(error);
    }

    if (isRefreshing) {
      return new Promise((resolve, reject) => {
        queue.push({ resolve, reject });
      }).then((token) => {
        original.headers.Authorization = `Bearer ${token}`;
        return apiClient(original);
      });
    }

    original._retry = true;

    // Cross-tab coordination: another tab may already be mid-refresh. Rather than also
    // calling /auth/refresh (racing it against the backend's single-use refresh token),
    // wait for that tab to publish new tokens and retry with those.
    if (isRefreshLockFresh(readRefreshLock())) {
      const tokenFromOtherTab = await waitForTokensFromOtherTab();
      if (tokenFromOtherTab) {
        original.headers.Authorization = `Bearer ${tokenFromOtherTab}`;
        return apiClient(original);
      }
      // Timed out waiting — the lock-holder likely crashed or closed. Fall through and
      // refresh ourselves rather than staying stuck.
    }

    isRefreshing = true;
    acquireRefreshLock();

    const refreshToken = localStorage.getItem("refreshToken");
    const tokenBeforeRefresh = localStorage.getItem("accessToken");
    if (!refreshToken) {
      processQueue(error, null);
      redirectToLoginIfProtected();
      isRefreshing = false;
      releaseRefreshLock();
      return Promise.reject(error);
    }

    try {
      const { data } = await axios.post(`${BASE_URL}/api/v1/auth/refresh`, {
        refreshToken,
      });
      setTokens(data.accessToken, data.refreshToken);
      processQueue(null, data.accessToken);
      original.headers.Authorization = `Bearer ${data.accessToken}`;
      return apiClient(original);
    } catch (refreshError) {
      if (isRefreshAlreadyRotated(refreshError)) {
        // Someone else (another tab, or a request that beat us to it) rotated the
        // refresh token first, inside the backend's grace window — not a lost session.
        // Re-read current tokens; if they've moved on, use them instead of logging out.
        const retryWithCurrentTokenIfRotated = (): boolean => {
          const currentToken = localStorage.getItem("accessToken");
          if (!currentToken || currentToken === tokenBeforeRefresh) return false;
          processQueue(null, currentToken);
          original.headers.Authorization = `Bearer ${currentToken}`;
          return true;
        };

        if (retryWithCurrentTokenIfRotated()) {
          return apiClient(original);
        }
        // Give the winner a brief moment to finish writing tokens, then check once more
        // before accepting this as a genuinely lost session.
        await new Promise((resolve) => setTimeout(resolve, REFRESH_RACE_RECHECK_DELAY_MS));
        if (retryWithCurrentTokenIfRotated()) {
          return apiClient(original);
        }
      }
      processQueue(refreshError, null);
      redirectToLoginIfProtected();
      return Promise.reject(refreshError);
    } finally {
      isRefreshing = false;
      releaseRefreshLock();
    }
  }
);
