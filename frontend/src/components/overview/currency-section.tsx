"use client";

import { useLocale, useTranslations } from "next-intl";
import type { Account } from "@/services/account-service";
import type { IncomeExpenseTrend, SpendingByCategory } from "@/services/analytics-service";
import { CashFlowChart } from "@/components/charts/cash-flow-chart";
import { SpendingDonutChart } from "@/components/charts/spending-donut-chart";
import { BudgetProgressManager } from "@/components/charts/budget-progress-manager";
import { RecentTransactionsList } from "@/components/accounts/recent-transactions-list";
import { AccountsGroup } from "@/components/accounts/balance-breakdown";
import { Card } from "@/components/ui/card";
import { formatCurrency } from "@/lib/utils";

interface CurrencySectionProps {
  currency: string;
  /** Native total balance for this currency; undefined when the section exists via
   *  budgets/transactions but the user holds no accounts in this currency. */
  nativeTotal?: string;
  trend: IncomeExpenseTrend[];
  spending: SpendingByCategory[];
  accounts: Account[];
  onEditAccount: (account: Account) => void;
  onDeleteAccount: (account: Account) => void;
  onOpenAccountDetail: (account: Account) => void;
  /** When provided, the empty-accounts state offers an add-account action. */
  onAddAccount?: () => void;
}

/** One self-contained currency section: charts + recent activity, accounts, then budgets. */
export function CurrencySection({
  currency,
  nativeTotal,
  trend,
  spending,
  accounts,
  onEditAccount,
  onDeleteAccount,
  onOpenAccountDetail,
  onAddAccount,
}: CurrencySectionProps) {
  const t = useTranslations("overview");
  const locale = useLocale();

  return (
    <section className="space-y-4">
      <div className="flex flex-wrap items-baseline justify-between gap-2 border-b border-border pb-2">
        <h2 className="text-lg font-semibold tracking-tight text-foreground">{currency}</h2>
        {nativeTotal !== undefined && (
          <p className="font-mono text-sm tabular-nums text-muted-foreground">
            {t("total", { amount: formatCurrency(nativeTotal, currency, locale) })}
          </p>
        )}
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <Card className="p-5">
          <h3 className="mb-4 font-semibold tracking-tight text-foreground">{t("cashFlow")}</h3>
          <CashFlowChart data={trend} currency={currency} orientation="horizontal" />
        </Card>
        <Card className="p-5">
          <h3 className="mb-4 font-semibold tracking-tight text-foreground">{t("spendingByCategory")}</h3>
          <SpendingDonutChart data={spending} currency={currency} />
        </Card>
        <Card className="p-5">
          <h3 className="mb-4 font-semibold tracking-tight text-foreground">{t("recentActivity")}</h3>
          <RecentTransactionsList currency={currency} />
        </Card>
      </div>

      <div>
        <h3 className="mb-2 text-sm font-semibold tracking-wide uppercase text-muted-foreground">
          {t("accounts")}
        </h3>
        <AccountsGroup
          accounts={accounts}
          onEdit={onEditAccount}
          onDelete={onDeleteAccount}
          onOpenDetail={onOpenAccountDetail}
          onAdd={onAddAccount}
        />
      </div>

      <Card className="p-5">
        <h3 className="mb-4 font-semibold tracking-tight text-foreground">{t("budgetProgress")}</h3>
        <BudgetProgressManager currency={currency} />
      </Card>
    </section>
  );
}
