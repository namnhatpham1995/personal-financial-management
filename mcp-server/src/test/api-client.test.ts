import { describe, expect, it } from "vitest";
import { FintrackApiError, mapApiError } from "../api-client.js";

function fakeAxiosError(status: number, secretToken = "fintrack_pat_should-never-leak") {
  return {
    isAxiosError: true,
    message: "Request failed",
    response: { status, data: { message: "some internal detail" } },
    config: { headers: { Authorization: `Bearer ${secretToken}` } },
    stack: `Error: boom\n    at ${secretToken}`,
  };
}

describe("mapApiError", () => {
  it("maps 401 to an invalid/expired/revoked message", () => {
    const result = mapApiError(fakeAxiosError(401));
    expect(result).toBeInstanceOf(FintrackApiError);
    expect(result.message).toMatch(/invalid, expired, or revoked/);
  });

  it("maps 403 to a scope message", () => {
    const result = mapApiError(fakeAxiosError(403));
    expect(result.message).toMatch(/scope does not permit/);
  });

  it("maps 429 to a rate-limit message", () => {
    const result = mapApiError(fakeAxiosError(429));
    expect(result.message).toMatch(/Rate limit exceeded/);
  });

  it("includes credential-safe retry guidance from a 429 response", () => {
    const error = fakeAxiosError(429);
    error.response.data = { retryAfterSeconds: 12 };
    error.response.headers = { "retry-after": "12" };
    expect(mapApiError(error).message).toBe("Rate limit exceeded for this token. Retry after 12 seconds.");
  });

  it("maps other 4xx to a generic rejected message", () => {
    const result = mapApiError(fakeAxiosError(400));
    expect(result.message).toContain("400");
  });

  it("maps a 409 payload conflict to a definitive rejection, not unknown-outcome language", () => {
    const result = mapApiError(fakeAxiosError(409));
    expect(result.outcome).toBe("definitive");
    expect(result.message).toContain("409");
    expect(result.message).not.toMatch(/unknown/i);
    expect(result.message).not.toMatch(/retry.*same.*key/i);
  });

  it("classifies every definitive 4xx (400/401/403/404/409/429) as outcome: definitive", () => {
    for (const status of [400, 401, 403, 404, 409, 429]) {
      expect(mapApiError(fakeAxiosError(status)).outcome).toBe("definitive");
    }
  });

  it("maps 5xx to an unavailable message with same-key retry guidance and outcome: unknown", () => {
    const result = mapApiError(fakeAxiosError(500));
    expect(result.message).toMatch(/currently unavailable/);
    expect(result.outcome).toBe("unknown");
    expect(result.message).toMatch(/unknown/i);
    expect(result.message).toMatch(/same idempotencyKey/i);
    // Must instruct NOT to generate a new key — never advise a fresh key on an unknown outcome.
    expect(result.message).toMatch(/do not generate a new key/i);
  });

  it("maps a network error (no response) to a connectivity message with outcome: unknown and same-key guidance", () => {
    const result = mapApiError({ isAxiosError: true, message: "Network Error" });
    expect(result.message).toMatch(/Could not reach the Fintrack API/);
    expect(result.outcome).toBe("unknown");
    expect(result.message).toMatch(/unknown/i);
    expect(result.message).toMatch(/same idempotencyKey/i);
    expect(result.message).toMatch(/do not generate a new key/i);
  });

  it("maps a non-axios error to a generic message with outcome: unknown", () => {
    const result = mapApiError(new Error("something else"));
    expect(result.message).toMatch(/unexpected error/);
    expect(result.outcome).toBe("unknown");
  });

  it("never includes the token, auth header, or stack trace, for any status class", () => {
    const secret = "fintrack_pat_should-never-leak";
    for (const status of [400, 401, 403, 404, 429, 500, 503]) {
      const result = mapApiError(fakeAxiosError(status, secret));
      expect(result.message).not.toContain(secret);
      expect(result.message).not.toContain("Authorization");
      expect(result.message).not.toContain("at Error");
      expect(result.message).not.toContain("some internal detail");
    }
  });
});
