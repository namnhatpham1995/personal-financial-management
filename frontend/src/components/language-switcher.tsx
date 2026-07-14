"use client";

import { useLocale, useTranslations } from "next-intl";
import { useRouter } from "next/navigation";
import { locales, localeNames, type Locale } from "@/i18n/config";
import { setLocaleCookie } from "@/lib/locale-preference";
import { apiClient } from "@/lib/api-client";

interface LanguageSwitcherProps {
  /** When true, also PUTs the choice to the backend so it follows the user across devices. */
  syncToBackend?: boolean;
  className?: string;
}

/** Applies a language change immediately (cookie + soft refresh), optionally syncing to the backend. */
export function LanguageSwitcher({ syncToBackend = false, className }: LanguageSwitcherProps) {
  const locale = useLocale();
  const router = useRouter();
  const t = useTranslations("settings");

  const handleChange = (next: Locale) => {
    setLocaleCookie(next);
    router.refresh();
    if (syncToBackend) {
      apiClient.put("/auth/me/language", { language: next }).catch(() => {
        // best-effort — the local language change already applied regardless of backend result
      });
    }
  };

  return (
    <select
      aria-label={t("languageLabel")}
      value={locale}
      onChange={(e) => handleChange(e.target.value as Locale)}
      className={className}
    >
      {locales.map((l) => (
        <option key={l} value={l} className="bg-card text-foreground">
          {localeNames[l]}
        </option>
      ))}
    </select>
  );
}
