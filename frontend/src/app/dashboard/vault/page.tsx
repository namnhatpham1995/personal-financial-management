"use client";

import { Suspense } from "react";
import { useTranslations } from "next-intl";
import { VaultWorkspace } from "@/components/vault/vault-workspace";

export default function VaultPage() {
  return (
    <Suspense fallback={<p className="text-muted-foreground">Loading…</p>}>
      <VaultContent />
    </Suspense>
  );
}

function VaultContent() {
  const t = useTranslations("vault");

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold tracking-tight text-foreground">{t("title")}</h1>
      </div>

      <VaultWorkspace />
    </div>
  );
}
