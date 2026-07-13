export const locales = ["en", "vi", "de", "zh"] as const;

export type Locale = (typeof locales)[number];

export const defaultLocale: Locale = "en";

export const LOCALE_COOKIE_NAME = "NEXT_LOCALE";

export const localeNames: Record<Locale, string> = {
  en: "English",
  vi: "Tiếng Việt",
  de: "Deutsch",
  zh: "简体中文",
};

export function isSupportedLocale(value: string | undefined | null): value is Locale {
  return !!value && (locales as readonly string[]).includes(value);
}
