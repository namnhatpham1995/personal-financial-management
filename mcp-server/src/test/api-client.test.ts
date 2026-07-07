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

  it("maps other 4xx to a generic rejected message", () => {
    const result = mapApiError(fakeAxiosError(400));
    expect(result.message).toContain("400");
  });

  it("maps 5xx to an unavailable message", () => {
    const result = mapApiError(fakeAxiosError(500));
    expect(result.message).toMatch(/currently unavailable/);
  });

  it("maps a network error (no response) to a connectivity message", () => {
    const result = mapApiError({ isAxiosError: true, message: "Network Error" });
    expect(result.message).toMatch(/Could not reach the Fintrack API/);
  });

  it("maps a non-axios error to a generic message", () => {
    const result = mapApiError(new Error("something else"));
    expect(result.message).toMatch(/unexpected error/);
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
