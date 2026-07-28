export type ChangelogTag = "new" | "improved";

export interface ChangelogEntry {
  version: number;
  date: string;
  titleKey: string;
  bodyKey: string;
  tag: ChangelogTag;
}

/**
 * Ordered newest-first. `version` is a monotonically increasing integer â€”
 * the sole source of truth for "has the user seen this" (see auth-context's
 * lastSeenChangelogVersion). Bump it by 1 for every new entry, never reuse.
 * Title/body text lives in messages/{en,vi,de,zh}.json under "changelog.entries.<version>".
 */
const unsortedEntries: ChangelogEntry[] = [
  {
    version: 13,
    date: "2026-07-28",
    titleKey: "changelog.entries.13.title",
    bodyKey: "changelog.entries.13.body",
    tag: "improved",
  },
  {
    version: 12,
    date: "2026-07-28",
    titleKey: "changelog.entries.12.title",
    bodyKey: "changelog.entries.12.body",
    tag: "improved",
  },
  {
    version: 11,
    date: "2026-07-27",
    titleKey: "changelog.entries.11.title",
    bodyKey: "changelog.entries.11.body",
    tag: "improved",
  },
  {
    version: 10,
    date: "2026-07-27",
    titleKey: "changelog.entries.10.title",
    bodyKey: "changelog.entries.10.body",
    tag: "improved",
  },
  {
    version: 9,
    date: "2026-07-25",
    titleKey: "changelog.entries.9.title",
    bodyKey: "changelog.entries.9.body",
    tag: "new",
  },
  {
    version: 8,
    date: "2026-07-22",
    titleKey: "changelog.entries.8.title",
    bodyKey: "changelog.entries.8.body",
    tag: "improved",
  },
  {
    version: 7,
    date: "2026-07-20",
    titleKey: "changelog.entries.7.title",
    bodyKey: "changelog.entries.7.body",
    tag: "improved",
  },
  {
    version: 6,
    date: "2026-07-16",
    titleKey: "changelog.entries.6.title",
    bodyKey: "changelog.entries.6.body",
    tag: "new",
  },
  {
    version: 5,
    date: "2026-07-16",
    titleKey: "changelog.entries.5.title",
    bodyKey: "changelog.entries.5.body",
    tag: "new",
  },
  {
    version: 4,
    date: "2026-07-15",
    titleKey: "changelog.entries.4.title",
    bodyKey: "changelog.entries.4.body",
    tag: "improved",
  },
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
