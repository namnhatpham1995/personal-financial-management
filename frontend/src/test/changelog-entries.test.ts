import { describe, expect, it } from "vitest";
import { changelogEntries, latestChangelogVersion } from "@/changelog/changelog-entries";

describe("changelog entries", () => {
  it("is ordered newest-first by version", () => {
    const versions = changelogEntries.map((e) => e.version);
    const sortedDesc = [...versions].sort((a, b) => b - a);
    expect(versions).toEqual(sortedDesc);
  });

  it("has unique versions", () => {
    const versions = changelogEntries.map((e) => e.version);
    expect(new Set(versions).size).toBe(versions.length);
  });

  it("exposes the highest version as latestChangelogVersion", () => {
    expect(latestChangelogVersion).toBe(Math.max(...changelogEntries.map((e) => e.version)));
  });
});
