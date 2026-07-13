import { render, type RenderOptions } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import type { ReactElement, ReactNode } from "react";
import type { Locale } from "@/i18n/config";
import enMessages from "../../messages/en.json";
import viMessages from "../../messages/vi.json";
import deMessages from "../../messages/de.json";
import zhMessages from "../../messages/zh.json";

const messagesByLocale: Record<Locale, typeof enMessages> = {
  en: enMessages,
  vi: viMessages,
  de: deMessages,
  zh: zhMessages,
};

/** Wraps ui in NextIntlClientProvider — use for any component that calls useTranslations. Defaults to English. */
export function renderWithIntl(
  ui: ReactElement,
  options?: Omit<RenderOptions, "wrapper"> & { locale?: Locale }
) {
  const { locale = "en", ...renderOptions } = options ?? {};
  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <NextIntlClientProvider locale={locale} messages={messagesByLocale[locale]}>
        {children}
      </NextIntlClientProvider>
    );
  }
  return render(ui, { wrapper: Wrapper, ...renderOptions });
}
