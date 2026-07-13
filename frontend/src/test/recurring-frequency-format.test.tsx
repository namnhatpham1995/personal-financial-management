/**
 * Verifies the recurring-rule "every" message's nested ICU select-inside-plural
 * renders grammatically correct text per locale (native-speaker review follow-up).
 * Plain-noun locales (vi/zh) don't inflect; en/de do, and de additionally drops
 * the "Alle" prefix entirely for the singular case ("Täglich" not "Alle Tag").
 */
import { screen } from "@testing-library/react";
import { useTranslations } from "next-intl";
import { describe, expect, it } from "vitest";
import { renderWithIntl } from "@/test/test-utils";
import type { Locale } from "@/i18n/config";

function Sample({ intervalValue, frequency }: { intervalValue: number; frequency: string }) {
  const t = useTranslations("transactions.recurring");
  return <p>{t("every", { intervalValue, frequency, accountName: "Acme" })}</p>;
}

const cases: Array<{ locale: Locale; intervalValue: number; frequency: string; expected: string }> = [
  { locale: "en", intervalValue: 1, frequency: "daily", expected: "Every day · Acme" },
  { locale: "en", intervalValue: 3, frequency: "daily", expected: "Every 3 days · Acme" },
  { locale: "en", intervalValue: 1, frequency: "monthly", expected: "Every month · Acme" },
  { locale: "en", intervalValue: 2, frequency: "monthly", expected: "Every 2 months · Acme" },
  { locale: "de", intervalValue: 1, frequency: "daily", expected: "Täglich · Acme" },
  { locale: "de", intervalValue: 3, frequency: "daily", expected: "Alle 3 Tage · Acme" },
  { locale: "de", intervalValue: 1, frequency: "yearly", expected: "Jährlich · Acme" },
  { locale: "de", intervalValue: 2, frequency: "weekly", expected: "Alle 2 Wochen · Acme" },
  { locale: "vi", intervalValue: 1, frequency: "daily", expected: "Mỗi ngày · Acme" },
  { locale: "vi", intervalValue: 3, frequency: "monthly", expected: "Mỗi 3 tháng · Acme" },
  { locale: "zh", intervalValue: 1, frequency: "daily", expected: "每天 · Acme" },
  { locale: "zh", intervalValue: 3, frequency: "monthly", expected: "每3月 · Acme" },
];

describe("recurring rule frequency phrasing", () => {
  it.each(cases)(
    "$locale: interval=$intervalValue frequency=$frequency",
    ({ locale, intervalValue, frequency, expected }) => {
      renderWithIntl(<Sample intervalValue={intervalValue} frequency={frequency} />, { locale });
      expect(screen.getByText(expected)).toBeInTheDocument();
    }
  );
});
