"use client";

import { useTranslations } from "next-intl";

export function useTransactionTypeLabel() {
  const t = useTranslations("enums.transactionType");
  return (value: "INCOME" | "EXPENSE" | "TRANSFER"): string => {
    return t(value);
  };
}

export function useFrequencyLabel() {
  const t = useTranslations("enums.frequency");
  return (value: "DAILY" | "WEEKLY" | "MONTHLY" | "YEARLY"): string => {
    return t(value);
  };
}

export function useAccountTypeLabel() {
  const t = useTranslations("enums.accountType");
  return (value: "CASH" | "BANK" | "CREDIT_CARD" | "SAVINGS" | "OTHER"): string => {
    return t(value);
  };
}

export function useDocumentStatusLabel() {
  const t = useTranslations("enums.documentStatus");
  return (value: "STAGED" | "CONFIRMING" | "ACTIVE"): string => {
    return t(value);
  };
}

export function useIngestionStatusLabel() {
  const t = useTranslations("enums.ingestionStatus");
  return (value: "EXTRACTING" | "AWAITING_REVIEW" | "COMMITTED" | "REJECTED" | "FAILED"): string => {
    return t(value);
  };
}
