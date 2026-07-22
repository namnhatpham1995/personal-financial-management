/**
 * Tests for the session-hint redirect middleware (frontend/src/middleware.ts).
 * A signed-in visitor (session-hint cookie present) should be redirected away from
 * public/auth routes to /dashboard before any page content renders.
 */
import { describe, it, expect } from "vitest";
import { NextRequest } from "next/server";
import { middleware, config } from "@/middleware";
import { SESSION_HINT_COOKIE_NAME } from "@/lib/api-client";

const BASE_URL = "http://localhost:3000";

function requestFor(path: string, hinted: boolean) {
  const headers = hinted ? { cookie: `${SESSION_HINT_COOKIE_NAME}=1` } : undefined;
  return new NextRequest(new URL(path, BASE_URL), { headers });
}

describe("session redirect middleware", () => {
  it.each(["/", "/login", "/register"])(
    "redirects a hinted visitor away from %s to /dashboard",
    (path) => {
      const response = middleware(requestFor(path, true));
      expect(response.status).toBe(307);
      expect(response.headers.get("location")).toBe(`${BASE_URL}/dashboard`);
    }
  );

  it.each(["/", "/login", "/register"])(
    "passes through an unhinted visitor on %s with no redirect",
    (path) => {
      const response = middleware(requestFor(path, false));
      expect(response.status).not.toBe(307);
      expect(response.headers.get("location")).toBeNull();
    }
  );

  it("does not match /dashboard — matcher excludes it entirely, not a redirect rule", () => {
    expect(config.matcher).not.toContain("/dashboard");
    expect(config.matcher.some((pattern) => pattern.startsWith("/dashboard"))).toBe(false);
  });
});
