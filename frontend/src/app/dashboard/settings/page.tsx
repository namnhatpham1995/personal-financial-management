"use client";

import Link from "next/link";
import { useTranslations } from "next-intl";
import { Card } from "@/components/ui/card";
import { LanguageSwitcher } from "@/components/language-switcher";

const selectCls =
  "rounded-md border border-border bg-card px-3 py-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-primary/40 focus:border-primary/40 transition-colors";

export default function SettingsPage() {
  const t = useTranslations("settings");

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold tracking-tight text-foreground">{t("title")}</h1>

      <Card className="p-5">
        <h2 className="mb-1 font-semibold tracking-tight text-foreground">{t("languageLabel")}</h2>
        <p className="mb-3 text-sm text-muted-foreground">{t("languageDescription")}</p>
        <LanguageSwitcher syncToBackend className={selectCls} />
      </Card>

      <Link
        href="/dashboard/settings/api-tokens"
        className="group block rounded-lg border border-border bg-card p-5 shadow-card transition-all duration-200 hover:border-primary/30 hover:bg-hover-surface hover:shadow-card-hover focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/40"
      >
        <h2 className="mb-1 font-semibold tracking-tight text-foreground transition-colors group-hover:text-primary">
          {t("apiTokensLink")}
        </h2>
        <p className="text-sm text-muted-foreground">{t("apiTokensLinkDescription")}</p>
      </Link>
    </div>
  );
}
