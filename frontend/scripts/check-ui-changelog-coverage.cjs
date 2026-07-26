const { execFileSync } = require("node:child_process");
const path = require("node:path");

const CHANGELOG_PATH = "frontend/src/changelog/changelog-entries.ts";
const LOCALE_PATHS = ["en", "vi", "de", "zh"].map((locale) => `frontend/messages/${locale}.json`);
const UI_PREFIXES = [
  "frontend/src/app/",
  "frontend/src/components/",
  "frontend/messages/",
  "frontend/src/styles/",
];
const UI_FILES = new Set([
  "frontend/src/app/globals.css",
  "frontend/tailwind.config.ts",
  "frontend/postcss.config.js",
]);
const UI_EXCLUSIONS = ["frontend/src/changelog/", "frontend/src/test/"];

function isUiBearingPath(filePath) {
  const normalized = filePath.replaceAll("\\", "/");
  if (UI_EXCLUSIONS.some((prefix) => normalized.startsWith(prefix))) return false;
  return UI_FILES.has(normalized) || UI_PREFIXES.some((prefix) => normalized.startsWith(prefix));
}

function parseChangelogEntries(source) {
  const entries = [];
  const entryPattern = /\{\s*version:\s*(\d+),\s*date:\s*"([^"]+)",\s*titleKey:\s*"([^"]+)",\s*bodyKey:\s*"([^"]+)",\s*tag:\s*"([^"]+)",?\s*\}/g;
  let match;

  while ((match = entryPattern.exec(source)) !== null) {
    entries.push({
      version: Number(match[1]),
      date: match[2],
      titleKey: match[3],
      bodyKey: match[4],
      tag: match[5],
    });
  }

  if (entries.length === 0) {
    throw new Error(`No changelog entries found in ${CHANGELOG_PATH}. Keep entries in the supported object format.`);
  }

  return entries;
}

function latestVersion(entries) {
  return Math.max(...entries.map((entry) => entry.version));
}

function readMessageKey(messages, key) {
  return key.split(".").reduce((value, segment) => {
    if (value && typeof value === "object") return value[segment];
    return undefined;
  }, messages);
}

function validateChangelogCoverage({
  changedFiles,
  baseEntriesSource,
  headEntriesSource,
  headMessages,
  hasExemption = false,
}) {
  const errors = [];
  const hasUiChanges = changedFiles.some(isUiBearingPath);
  const baseEntries = parseChangelogEntries(baseEntriesSource);
  const headEntries = parseChangelogEntries(headEntriesSource);
  const seenVersions = new Set();

  for (const entry of headEntries) {
    if (seenVersions.has(entry.version)) errors.push(`Duplicate changelog version ${entry.version}.`);
    seenVersions.add(entry.version);
  }

  const baseLatestVersion = latestVersion(baseEntries);
  const headLatestVersion = latestVersion(headEntries);
  const newEntries = headEntries.filter((entry) => entry.version > baseLatestVersion);

  if (hasUiChanges && !hasExemption && headLatestVersion <= baseLatestVersion) {
    errors.push(
      `UI-bearing files changed, but the latest changelog version did not increase above ${baseLatestVersion}. ` +
        "Add a localized What's New entry or request the skip-whats-new exemption for a non-visible change."
    );
  }

  for (const entry of newEntries) {
    for (const [locale, messages] of Object.entries(headMessages)) {
      for (const key of [entry.titleKey, entry.bodyKey]) {
        const value = readMessageKey(messages, key);
        if (typeof value !== "string" || value.trim() === "") {
          errors.push(`Changelog entry ${entry.version} is missing ${key} in ${locale}.json.`);
        }
      }
    }
  }

  return { errors, hasUiChanges, baseLatestVersion, headLatestVersion, newEntries };
}

function git(repoRoot, args) {
  return execFileSync("git", args, { cwd: repoRoot, encoding: "utf8" });
}

function gitShow(repoRoot, revision, filePath) {
  return git(repoRoot, ["show", `${revision}:${filePath}`]);
}

function loadRevisionData(repoRoot, revision) {
  const headEntriesSource = gitShow(repoRoot, revision, CHANGELOG_PATH);
  const headMessages = Object.fromEntries(
    LOCALE_PATHS.map((localePath) => {
      const locale = path.basename(localePath, ".json");
      return [locale, JSON.parse(gitShow(repoRoot, revision, localePath))];
    })
  );
  return { headEntriesSource, headMessages };
}

function parseArguments(args) {
  const options = { base: undefined, head: undefined, hasExemption: false };
  for (let index = 0; index < args.length; index += 1) {
    if (args[index] === "--base") options.base = args[++index];
    if (args[index] === "--head") options.head = args[++index];
    if (args[index] === "--skip-whats-new") options.hasExemption = true;
  }
  return options;
}

function runCli() {
  const options = parseArguments(process.argv.slice(2));
  if (!options.base || !options.head) {
    console.error("Usage: node scripts/check-ui-changelog-coverage.cjs --base <revision> --head <revision> [--skip-whats-new]");
    process.exitCode = 2;
    return;
  }

  const repoRoot = path.resolve(__dirname, "../..");
  const changedFiles = git(repoRoot, ["diff", "--name-only", options.base, options.head])
    .split(/\r?\n/)
    .filter(Boolean);
  const baseEntriesSource = gitShow(repoRoot, options.base, CHANGELOG_PATH);
  const { headEntriesSource, headMessages } = loadRevisionData(repoRoot, options.head);
  const result = validateChangelogCoverage({
    changedFiles,
    baseEntriesSource,
    headEntriesSource,
    headMessages,
    hasExemption: options.hasExemption,
  });

  if (result.errors.length > 0) {
    console.error("What's New coverage check failed:");
    result.errors.forEach((error) => console.error(`- ${error}`));
    process.exitCode = 1;
    return;
  }

  const scope = result.hasUiChanges ? "UI changes detected" : "No UI changes detected";
  console.log(`${scope}; changelog coverage is valid.`);
}

if (require.main === module) runCli();

module.exports = { isUiBearingPath, parseChangelogEntries, validateChangelogCoverage };
