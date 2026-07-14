"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { useTranslations } from "next-intl";
import { X } from "lucide-react";
import { changelogEntries, latestChangelogVersion } from "@/changelog/changelog-entries";
import { changelogService } from "@/services/changelog-service";
import { useAuth } from "@/lib/auth-context";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";

const MAX_VISIBLE_ENTRIES = 3;

/**
 * Shown once per unseen batch, mounted inside AuthGuard so `user` is always
 * present when this renders. Every dismissal path marks the latest version
 * seen server-side (PUT /auth/me/changelog-seen) so it never reappears,
 * on this device or any other.
 */
export function WhatsNewModal() {
  const { user, setLastSeenChangelogVersion } = useAuth();
  const router = useRouter();
  const t = useTranslations();
  const [isOpen, setIsOpen] = useState(false);
  const dialogRef = useRef<HTMLDivElement>(null);

  const lastSeen = user?.lastSeenChangelogVersion ?? latestChangelogVersion;
  const unseenEntries = changelogEntries.filter((entry) => entry.version > lastSeen);

  useEffect(() => {
    setIsOpen(unseenEntries.length > 0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [user?.id, lastSeen]);

  useEffect(() => {
    if (!isOpen) return;
    dialogRef.current?.focus();

    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        dismiss();
        return;
      }
      if (e.key !== "Tab" || !dialogRef.current) return;
      const focusable = dialogRef.current.querySelectorAll<HTMLElement>(
        'button, a[href], [tabindex]:not([tabindex="-1"])'
      );
      if (focusable.length === 0) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault();
        first.focus();
      }
    };

    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isOpen]);

  const markSeen = () => {
    changelogService.markSeen(latestChangelogVersion).catch(() => {
      // best-effort — local seen-state already applied regardless of backend result
    });
    setLastSeenChangelogVersion(latestChangelogVersion);
  };

  const dismiss = () => {
    markSeen();
    setIsOpen(false);
  };

  const viewAll = () => {
    markSeen();
    setIsOpen(false);
    router.push("/dashboard/whats-new");
  };

  if (!isOpen) return null;

  const visibleEntries = unseenEntries.slice(0, MAX_VISIBLE_ENTRIES);
  const hasMore = unseenEntries.length > MAX_VISIBLE_ENTRIES;

  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center p-4">
      <div
        className="absolute inset-0 bg-black/60 backdrop-blur-sm"
        onClick={dismiss}
        aria-hidden="true"
      />

      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="whats-new-title"
        tabIndex={-1}
        className="relative w-full max-w-md rounded-lg border border-border bg-card p-6 shadow-card-hover focus:outline-none"
      >
        <div className="flex items-start justify-between gap-4">
          <h2 id="whats-new-title" className="text-lg font-semibold text-foreground">
            {t("changelog.modal.title")}
          </h2>
          <button
            onClick={dismiss}
            aria-label={t("common.close")}
            className="rounded-sm p-1 text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/40"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="mt-4 space-y-4">
          {visibleEntries.map((entry) => (
            <div key={entry.version}>
              <div className="flex items-center justify-between gap-3">
                <h3 className="text-sm font-medium text-foreground">{t(entry.titleKey)}</h3>
                <Badge variant={entry.tag === "new" ? "income" : "neutral"}>
                  {t(`changelog.tags.${entry.tag}`)}
                </Badge>
              </div>
              <p className="mt-1 text-sm text-muted-foreground">{t(entry.bodyKey)}</p>
            </div>
          ))}
        </div>

        <div className="mt-6 flex items-center justify-between gap-3">
          {hasMore ? (
            <button
              onClick={viewAll}
              className="text-sm font-medium text-primary transition-colors hover:text-primary/80 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/40"
            >
              {t("changelog.modal.viewAll")}
            </button>
          ) : (
            <span />
          )}
          <Button onClick={dismiss}>{t("changelog.modal.gotIt")}</Button>
        </div>
      </div>
    </div>
  );
}
