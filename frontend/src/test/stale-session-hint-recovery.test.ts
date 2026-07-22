/**
 * Verifies the self-healing path for a stale session-presence hint: if the cookie says
 * "signed in" but the underlying tokens are dead, AuthProvider's own session-restore
 * failure clears the cookie — so a redirect loop between a public route and /dashboard
 * cannot persist past one extra hop (task 3.1).
 */
import { render, waitFor } from "@testing-library/react";
import { describe, expect, it, vi, beforeEach } from "vitest";
import React from "react";
import { AuthProvider } from "@/lib/auth-context";
import { apiClient, setTokens, hasSessionHintCookie } from "@/lib/api-client";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ refresh: vi.fn(), push: vi.fn(), replace: vi.fn() }),
}));

describe("stale session hint recovery", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
  });

  it("clears the session hint cookie when restoration fails, breaking any redirect loop", async () => {
    // Simulate a cookie backfilled from an old session whose tokens are now dead server-side.
    setTokens("stale-acc", "stale-ref");
    expect(hasSessionHintCookie()).toBe(true);

    vi.spyOn(apiClient, "get").mockRejectedValue(new Error("401"));

    render(
      React.createElement(AuthProvider, null, React.createElement("div", null, "app"))
    );

    // AuthProvider's /auth/me catch calls clearTokens(), which clears the hint cookie too —
    // so the middleware has nothing left to redirect on the next navigation to /login.
    await waitFor(() => expect(hasSessionHintCookie()).toBe(false));
  });
});
