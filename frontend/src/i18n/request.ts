import { cookies, headers } from "next/headers";
import { getRequestConfig } from "next-intl/server";
import { defaultLocale, isSupportedLocale, LOCALE_COOKIE_NAME, type Locale } from "./config";

/** Picks the first supported locale from an Accept-Language header, falling back to defaultLocale. */
function negotiateFromAcceptLanguage(header: string | null): Locale {
  if (!header) return defaultLocale;
  const preferred = header.split(",").map((part) => part.split(";")[0]?.trim().toLowerCase());
  for (const lang of preferred) {
    const base = lang?.split("-")[0];
    if (isSupportedLocale(base)) return base;
  }
  return defaultLocale;
}

export default getRequestConfig(async () => {
  const cookieStore = await cookies();
  const cookieLocale = cookieStore.get(LOCALE_COOKIE_NAME)?.value;

  const locale: Locale = isSupportedLocale(cookieLocale)
    ? cookieLocale
    : negotiateFromAcceptLanguage((await headers()).get("accept-language"));

  // Falls back to English messages if a locale's message file doesn't exist yet
  // (e.g. mid-rollout, before all locales have translations) — locale is kept
  // as negotiated so Intl date/number formatting still follows it.
  const messages = await import(`../../messages/${locale}.json`)
    .then((mod) => mod.default)
    .catch(async () => (await import("../../messages/en.json")).default);

  return { locale, messages };
});
