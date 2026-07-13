export type ChangelogTag = "new" | "improved";

export interface ChangelogEntry {
  version: number;
  date: string;
  titleKey: string;
  bodyKey: string;
  tag: ChangelogTag;
}

/**
 * Ordered newest-first. `version` is a monotonically increasing integer —
 * the sole source of truth for "has the user seen this" (see auth-context's
 * lastSeenChangelogVersion). Bump it by 1 for every new entry, never reuse.
 * Title/body text lives in messages/{en,vi,de,zh}.json under "changelog.entries.<version>".
 */
const unsortedEntries: ChangelogEntry[] = [
  {
    version: 3,
    date: "2026-07-13",
    titleKey: "changelog.entries.3.title",
    bodyKey: "changelog.entries.3.body",
    tag: "new",
  },
  {
    version: 2,
    date: "2026-06-02",
    titleKey: "changelog.entries.2.title",
    bodyKey: "changelog.entries.2.body",
    tag: "new",
  },
  {
    version: 1,
    date: "2026-05-05",
    titleKey: "changelog.entries.1.title",
    bodyKey: "changelog.entries.1.body",
    tag: "improved",
  },
];

export const changelogEntries: ChangelogEntry[] = [...unsortedEntries].sort(
  (a, b) => b.version - a.version
);

export const latestChangelogVersion: number = changelogEntries[0]?.version ?? 0;
