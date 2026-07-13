-- V10: Add preferred_language to users for cross-device UI language sync.
-- NULL means the user has never explicitly chosen a language (client falls
-- back to browser-negotiated locale); a non-null value is an explicit choice
-- that follows the user across devices.

ALTER TABLE users ADD COLUMN preferred_language VARCHAR(8);
