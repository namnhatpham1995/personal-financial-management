// This suite runs with Node's built-in test runner, not Vitest.
const assert = require("node:assert/strict");
const test = require("node:test");
const {
  isUiBearingPath,
  validateChangelogCoverage,
} = require("./check-ui-changelog-coverage.cjs");

function entriesSource(entries) {
  return `const entries = [${entries
    .map(
      (entry) => `{
        version: ${entry.version},
        date: "2026-07-26",
        titleKey: "changelog.entries.${entry.version}.title",
        bodyKey: "changelog.entries.${entry.version}.body",
        tag: "new",
      }`
    )
    .join(",")}];`;
}

function messagesFor(versions, missing = {}) {
  return Object.fromEntries(
    ["en", "vi", "de", "zh"].map((locale) => [
      locale,
      {
        changelog: {
          entries: Object.fromEntries(
            versions.map((version) => [
              version,
              {
                title: missing[locale] === `${version}.title` ? "" : `Title ${version}`,
                body: missing[locale] === `${version}.body` ? "" : `Body ${version}`,
              },
            ])
          ),
        },
      },
    ])
  );
}

function validate({ changedFiles, baseVersions = [3], headVersions = [3], hasExemption = false, missing }) {
  return validateChangelogCoverage({
    changedFiles,
    baseEntriesSource: entriesSource(baseVersions.map((version) => ({ version }))),
    headEntriesSource: entriesSource(headVersions.map((version) => ({ version }))),
    headMessages: messagesFor(headVersions, missing),
    hasExemption,
  });
}

test("classifies rendered files and excludes changelog and tests", () => {
  assert.equal(isUiBearingPath("frontend/src/app/dashboard/page.tsx"), true);
  assert.equal(isUiBearingPath("frontend/src/components/sidebar.tsx"), true);
  assert.equal(isUiBearingPath("frontend/messages/en.json"), true);
  assert.equal(isUiBearingPath("frontend/src/changelog/changelog-entries.ts"), false);
  assert.equal(isUiBearingPath("frontend/src/test/whats-new-modal.test.tsx"), false);
  assert.equal(isUiBearingPath("frontend/src/services/changelog-service.ts"), false);
});

test("fails a UI change without a newer changelog version", () => {
  const result = validate({ changedFiles: ["frontend/src/components/sidebar.tsx"] });
  assert.match(result.errors.join("\n"), /latest changelog version did not increase/);
});

test("allows a non-UI change without a newer changelog version", () => {
  const result = validate({ changedFiles: ["frontend/src/services/changelog-service.ts"] });
  assert.deepEqual(result.errors, []);
});

test("accepts a UI change with a newer, fully localized entry", () => {
  const result = validate({
    changedFiles: ["frontend/src/app/dashboard/page.tsx", "frontend/src/changelog/changelog-entries.ts"],
    headVersions: [3, 4],
  });
  assert.deepEqual(result.errors, []);
});

test("fails a newer entry with an incomplete translation", () => {
  const result = validate({
    changedFiles: ["frontend/src/app/dashboard/page.tsx"],
    headVersions: [3, 4],
    missing: { de: "4.body" },
  });
  assert.match(result.errors.join("\n"), /missing changelog.entries.4.body in de.json/);
});

test("fails duplicate changelog versions", () => {
  const result = validate({ changedFiles: [], headVersions: [3, 3] });
  assert.match(result.errors.join("\n"), /Duplicate changelog version 3/);
});

test("permits an explicitly exempted non-visible frontend change", () => {
  const result = validate({
    changedFiles: ["frontend/src/components/sidebar.tsx"],
    hasExemption: true,
  });
  assert.deepEqual(result.errors, []);
});
