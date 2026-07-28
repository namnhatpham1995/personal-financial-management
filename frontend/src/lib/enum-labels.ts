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
  return (value: "UPLOADED" | "STAGED" | "CONFIRMING" | "ACTIVE"): string => {
    return t(value);
  };
}

export function useIngestionStatusLabel() {
  const t = useTranslations("enums.ingestionStatus");
  return (value: "EXTRACTING" | "AWAITING_REVIEW" | "COMMITTED" | "REJECTED" | "FAILED" | "INVALIDATED"): string => {
    return t(value);
  };
}

export function useVaultStageLabel() {
  const t = useTranslations("enums.vaultStage");
  return (
    value:
      | "READY_TO_IMPORT"
      | "NEEDS_REVIEW"
      | "PROCESSING"
      | "IMPORTED"
      | "NOT_PROCESSED"
      | "FAILED"
      | "DISMISSED"
      | "STORED"
  ): string => {
    return t(value);
  };
}
