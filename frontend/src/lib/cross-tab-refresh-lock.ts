/**
 * Cross-tab coordination for the access-token refresh flow (task 7.6).
 *
 * Without this, every open tab that hits a 401 around the same time independently calls
 * POST /auth/refresh, racing each other against the backend's single-use refresh token.
 * A localStorage lock lets one tab "own" the refresh; other tabs wait for it to publish
 * new tokens (via the `storage` event browsers fire in OTHER tabs on localStorage writes)
 * and retry with those instead of also calling refresh.
 */

const REFRESH_LOCK_KEY = "fintrack.refreshLock";
/** Short timeout so a crashed/closed tab's stale lock doesn't wedge every other tab. */
const REFRESH_LOCK_TIMEOUT_MS = 5000;
/** How long a waiting tab gives the lock-holder to finish before refreshing itself. */
const DEFAULT_WAIT_FOR_OTHER_TAB_MS = 4000;

export function readRefreshLock(): number | null {
  if (typeof window === "undefined") return null;
  const raw = window.localStorage.getItem(REFRESH_LOCK_KEY);
  if (!raw) return null;
  const ts = Number(raw);
  return Number.isFinite(ts) ? ts : null;
}

export function isRefreshLockFresh(lockTimestamp: number | null): boolean {
  return lockTimestamp !== null && Date.now() - lockTimestamp < REFRESH_LOCK_TIMEOUT_MS;
}

export function acquireRefreshLock(): void {
  window.localStorage.setItem(REFRESH_LOCK_KEY, String(Date.now()));
}

export function releaseRefreshLock(): void {
  window.localStorage.removeItem(REFRESH_LOCK_KEY);
}

/**
 * Waits for another tab's refresh to complete, signaled by the `storage` event fired
 * when it writes a new accessToken. Resolves with the new token, or null on timeout —
 * callers should fall back to performing the refresh themselves in that case (the
 * lock-holder likely crashed or closed before finishing).
 */
export function waitForTokensFromOtherTab(
  timeoutMs: number = DEFAULT_WAIT_FOR_OTHER_TAB_MS
): Promise<string | null> {
  return new Promise((resolve) => {
    let settled = false;
    const finish = (token: string | null) => {
      if (settled) return;
      settled = true;
      window.removeEventListener("storage", onStorage);
      clearTimeout(timer);
      resolve(token);
    };
    const onStorage = (e: StorageEvent) => {
      if (e.key === "accessToken" && e.newValue) finish(e.newValue);
    };
    window.addEventListener("storage", onStorage);
    const timer = setTimeout(() => finish(null), timeoutMs);
  });
}
