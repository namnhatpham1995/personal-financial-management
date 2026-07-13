-- V10: Track each user's last-seen "What's New" changelog version so the
-- notification dismissal syncs across devices (same pattern as preferred_language).

ALTER TABLE users ADD COLUMN last_seen_changelog_version INT NOT NULL DEFAULT 0;
