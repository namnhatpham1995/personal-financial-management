"use client";

import { useEffect } from "react";
import { useTranslations } from "next-intl";
import { changelogEntries, latestChangelogVersion } from "@/changelog/changelog-entries";
import { changelogService } from "@/services/changelog-service";
import { useAuth } from "@/lib/auth-context";
import { Card } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";

export default function WhatsNewPage() {
  const t = useTranslations();
  const { user, setLastSeenChangelogVersion } = useAuth();

  useEffect(() => {
    if (!user || user.lastSeenChangelogVersion >= latestChangelogVersion) return;
    changelogService.markSeen(latestChangelogVersion).catch(() => {
      // best-effort — local seen-state already applied regardless of backend result
    });
    setLastSeenChangelogVersion(latestChangelogVersion);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [user?.id]);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold tracking-tight text-foreground">
          {t("changelog.page.title")}
        </h1>
        <p className="mt-1 text-sm text-muted-foreground">{t("changelog.page.description")}</p>
      </div>

      <div className="space-y-4">
        {changelogEntries.map((entry) => (
          <Card key={entry.version} className="p-5">
            <div className="flex items-start justify-between gap-4">
              <h2 className="text-base font-semibold text-foreground">{t(entry.titleKey)}</h2>
              <Badge variant={entry.tag === "new" ? "income" : "neutral"}>
                {t(`changelog.tags.${entry.tag}`)}
              </Badge>
            </div>
            <p className="mt-1 font-mono text-xs tabular-nums text-muted-foreground">
              {new Date(entry.date).toLocaleDateString()}
            </p>
            <p className="mt-3 text-sm text-muted-foreground">{t(entry.bodyKey)}</p>
          </Card>
        ))}
      </div>
    </div>
  );
}
