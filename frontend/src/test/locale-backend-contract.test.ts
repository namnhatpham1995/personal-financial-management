/**
 * Contract test guarding against frontend/backend locale-set drift.
 * The supported-locale set is declared independently in two places (frontend
 * `locales` array, backend `UpdateLanguageRequest`'s validation regex) since
 * they're different runtimes with no shared source of truth. Nothing enforces
 * they stay in sync — this test reads the backend DTO's regex and fails if it
 * ever diverges from the frontend's `locales` array, so adding a locale to
 * one side without the other is caught in CI instead of failing silently
 * (frontend offers a language the backend then rejects with 400, or vice versa).
 */
import { describe, it, expect } from "vitest";
import { readFileSync } from "fs";
import path from "path";
import { locales } from "@/i18n/config";

const backendDtoPath = path.resolve(
  __dirname,
  "../../../backend/src/main/java/com/fintrack/auth/web/dto/UpdateLanguageRequest.java"
);

function extractBackendLocales(): string[] {
  const source = readFileSync(backendDtoPath, "utf-8");
  const match = source.match(/regexp\s*=\s*"\^\(([a-z|]+)\)\$"/);
  if (!match) {
    throw new Error(
      `Could not find the expected @Pattern(regexp = "^(...)$") in ${backendDtoPath} — ` +
        "update this test's extraction regex if UpdateLanguageRequest's validation changed shape."
    );
  }
  return match[1].split("|").sort();
}

describe("frontend/backend locale contract", () => {
  it("backend's accepted language codes match the frontend's supported locales", () => {
    const backendLocales = extractBackendLocales();
    const frontendLocales = [...locales].sort();
    expect(backendLocales).toEqual(frontendLocales);
  });
});
