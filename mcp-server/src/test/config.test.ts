import { describe, expect, it } from "vitest";
import { loadConfig } from "../config.js";

describe("loadConfig", () => {
  it("fails fast when FINTRACK_API_URL is missing", () => {
    expect(() => loadConfig({ FINTRACK_API_TOKEN: "fintrack_pat_abc" })).toThrow(
      /FINTRACK_API_URL is not set/
    );
  });

  it("fails fast when FINTRACK_API_TOKEN is missing", () => {
    expect(() => loadConfig({ FINTRACK_API_URL: "http://localhost:8080" })).toThrow(
      /FINTRACK_API_TOKEN is not set/
    );
  });

  it("rejects a token without the fintrack_pat_ prefix", () => {
    expect(() =>
      loadConfig({ FINTRACK_API_URL: "http://localhost:8080", FINTRACK_API_TOKEN: "sk-not-a-pat" })
    ).toThrow(/does not look like a Fintrack personal access token/);
  });

  it("never echoes the token value in any error message", () => {
    const secret = "fintrack_pat_super-secret-value";
    try {
      loadConfig({ FINTRACK_API_URL: "http://localhost:8080", FINTRACK_API_TOKEN: undefined });
    } catch (err) {
      expect((err as Error).message).not.toContain(secret);
    }

    try {
      loadConfig({ FINTRACK_API_URL: undefined, FINTRACK_API_TOKEN: secret });
    } catch (err) {
      expect((err as Error).message).not.toContain(secret);
    }
  });

  it("accepts well-formed config and trims a trailing slash from the URL", () => {
    const config = loadConfig({
      FINTRACK_API_URL: "http://localhost:8080/",
      FINTRACK_API_TOKEN: "fintrack_pat_abc123",
    });

    expect(config).toEqual({ apiUrl: "http://localhost:8080", apiToken: "fintrack_pat_abc123" });
  });
});
