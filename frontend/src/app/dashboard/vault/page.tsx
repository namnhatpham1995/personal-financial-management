"use client";

import { Suspense, useState } from "react";
import { useTranslations } from "next-intl";
import { cn } from "@/lib/utils";
import { StatementTab } from "@/components/vault/statement-tab";
import { ReceiptTab } from "@/components/vault/receipt-tab";

export default function VaultPage() {
  return (
    <Suspense fallback={<p className="text-muted-foreground">Loading…</p>}>
      <VaultContent />
    </Suspense>
  );
}

function VaultContent() {
  const t = useTranslations("vault");
  const [activeTab, setActiveTab] = useState<"statement" | "receipt">("statement");

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold tracking-tight text-foreground">{t("title")}</h1>
      </div>

      <div className="flex gap-1 border-b border-border">
        {(["statement", "receipt"] as const).map((tab) => (
          <button
            key={tab}
            onClick={() => setActiveTab(tab)}
            className={cn(
              "px-4 py-2 text-sm font-medium transition-colors",
              activeTab === tab
                ? "border-b-2 border-primary text-primary"
                : "text-muted-foreground hover:text-foreground"
            )}
          >
            {t(`tabs.${tab}`)}
          </button>
        ))}
      </div>

      {activeTab === "statement" && <StatementTab />}
      {activeTab === "receipt" && <ReceiptTab />}
    </div>
  );
}
