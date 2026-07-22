import { render, type RenderOptions } from "@testing-library/react";
import { NextIntlClientProvider } from "next-intl";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
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

/**
 * Wraps ui in NextIntlClientProvider (for useTranslations) and a QueryClientProvider
 * (for hooks like useCategoryLabel that call useQuery). retry: false keeps an
 * unmocked query's failure fast rather than retrying for several seconds.
 * A test that needs its own QueryClient (e.g. to assert on cache state) can still
 * wrap its own — nesting providers is safe, the inner one wins.
 */
export function renderWithIntl(
  ui: ReactElement,
  options?: Omit<RenderOptions, "wrapper"> & { locale?: Locale }
) {
  const { locale = "en", ...renderOptions } = options ?? {};
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>
        <NextIntlClientProvider locale={locale} messages={messagesByLocale[locale]}>
          {children}
        </NextIntlClientProvider>
      </QueryClientProvider>
    );
  }
  return render(ui, { wrapper: Wrapper, ...renderOptions });
}
