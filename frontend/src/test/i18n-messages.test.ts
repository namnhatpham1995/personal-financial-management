/**
 * Translation completeness gate (task 2.5 / D7).
 * Runs under npm test so CI fails whenever a locale drifts from en.json:
 * missing/extra keys, empty values, or mismatched ICU placeholders.
 * Locales beyond en are added in a later PR — this test adapts to however
 * many messages/*.json files currently exist.
 */
import { describe, it, expect } from "vitest";
import { readdirSync, readFileSync } from "fs";
import path from "path";
import { locales, defaultLocale } from "@/i18n/config";

const messagesDir = path.resolve(__dirname, "../../messages");

type MessageTree = { [key: string]: string | MessageTree };

function loadMessages(locale: string): MessageTree {
  const raw = readFileSync(path.join(messagesDir, `${locale}.json`), "utf-8");
  return JSON.parse(raw);
}

function flatten(tree: MessageTree, prefix = ""): Record<string, string> {
  return Object.entries(tree).reduce<Record<string, string>>((acc, [key, value]) => {
    const fullKey = prefix ? `${prefix}.${key}` : key;
    if (typeof value === "string") {
      acc[fullKey] = value;
    } else {
      Object.assign(acc, flatten(value, fullKey));
    }
    return acc;
  }, {});
}

function icuPlaceholders(value: string): string[] {
  const matches = value.match(/\{[^}]+\}/g) ?? [];
  return matches.map((m) => m.slice(1, -1).split(",")[0].trim()).sort();
}

const availableLocales = readdirSync(messagesDir)
  .filter((f) => f.endsWith(".json"))
  .map((f) => f.replace(/\.json$/, ""))
  .filter((locale) => (locales as readonly string[]).includes(locale));

const englishFlat = flatten(loadMessages(defaultLocale));
const otherLocales = availableLocales.filter((l) => l !== defaultLocale);

describe("i18n message registry", () => {
  it("every supported locale present has a message file", () => {
    expect(availableLocales).toContain(defaultLocale);
  });

  it.each(otherLocales)("%s has exactly the same keys as en", (locale) => {
    const localeFlat = flatten(loadMessages(locale));
    const missing = Object.keys(englishFlat).filter((k) => !(k in localeFlat));
    const extra = Object.keys(localeFlat).filter((k) => !(k in englishFlat));

    expect(missing, `${locale}.json is missing keys: ${missing.join(", ")}`).toHaveLength(0);
    expect(extra, `${locale}.json has extra keys not in en.json: ${extra.join(", ")}`).toHaveLength(0);
  });

  it.each(availableLocales)("%s has no empty translation values", (locale) => {
    const localeFlat = flatten(loadMessages(locale));
    const empty = Object.entries(localeFlat)
      .filter(([, value]) => value.trim().length === 0)
      .map(([key]) => key);

    expect(empty, `${locale}.json has empty values for: ${empty.join(", ")}`).toHaveLength(0);
  });

  it.each(otherLocales)("%s has matching ICU placeholders to en for shared keys", (locale) => {
    const localeFlat = flatten(loadMessages(locale));
    const mismatches: string[] = [];

    for (const [key, enValue] of Object.entries(englishFlat)) {
      if (!(key in localeFlat)) continue;
      const enPlaceholders = icuPlaceholders(enValue);
      const localePlaceholders = icuPlaceholders(localeFlat[key]);
      if (JSON.stringify(enPlaceholders) !== JSON.stringify(localePlaceholders)) {
        mismatches.push(`${key} (en: [${enPlaceholders}], ${locale}: [${localePlaceholders}])`);
      }
    }

    expect(mismatches, `Placeholder mismatches in ${locale}.json: ${mismatches.join("; ")}`).toHaveLength(0);
  });
});
