import { z } from "zod";

export const isoDate = z.string().regex(/^\d{4}-\d{2}-\d{2}$/, "Expected an ISO date (YYYY-MM-DD)");
export const transactionType = z.enum(["INCOME", "EXPENSE", "TRANSFER"]);
export const accountType = z.enum(["CASH", "BANK", "CREDIT_CARD", "SAVINGS", "OTHER"]);
export const budgetPeriod = z.enum(["MONTHLY", "YEARLY"]);
export const currencyCode = z.string().regex(/^[A-Z]{3}$/, "Expected an uppercase ISO 4217 currency code");

export const listTransactionsShape = {
  accountId: z.number().int().optional(),
  categoryId: z.number().int().optional(),
  type: transactionType.optional(),
  startDate: isoDate.optional(),
  endDate: isoDate.optional(),
  currency: z.string().length(3).optional(),
  page: z.number().int().min(0).optional(),
  size: z.number().int().min(1).max(100).optional(),
};

export const getTransactionShape = {
  id: z.number().int(),
};

export const createTransactionShape = {
  transactionType,
  amount: z.number().positive(),
  transactionDate: isoDate,
  accountId: z.number().int(),
  transferAccountId: z.number().int().optional(),
  categoryId: z.number().int().optional(),
  note: z.string().max(2000).optional(),
};

export const updateTransactionShape = {
  id: z.number().int(),
  amount: z.number().positive().optional(),
  transactionDate: isoDate.optional(),
  categoryId: z.number().int().optional(),
  note: z.string().max(2000).optional(),
};

export const dateRangeShape = {
  from: isoDate,
  to: isoDate,
};

export const spendingByCategoryShape = {
  ...dateRangeShape,
  accountId: z.number().int().optional(),
};

export const accountBalancesShape = {
  targetCurrency: currencyCode.optional(),
};

export const getAccountShape = {
  id: z.number().int(),
};

export const createAccountShape = {
  name: z.string().trim().min(1).max(255),
  accountType,
  currency: currencyCode,
  initialBalance: z.number().nonnegative().optional(),
};

export const updateAccountShape = {
  id: z.number().int(),
  name: z.string().trim().min(1).max(255).optional(),
  accountType: accountType.optional(),
  currency: currencyCode.optional(),
  initialBalance: z.number().nonnegative().optional(),
};

export const listCategoriesShape = {
  type: transactionType.optional(),
};

export const createCategoryShape = {
  name: z.string().trim().min(1).max(100),
  transactionType,
};

export const updateCategoryShape = {
  id: z.number().int(),
  name: z.string().trim().min(1).max(100),
  transactionType: transactionType.optional(),
};

export const createBudgetShape = {
  categoryId: z.number().int(),
  period: budgetPeriod,
  amountLimit: z.number().positive(),
  startDate: isoDate,
  currency: currencyCode,
};

export const updateBudgetShape = {
  id: z.number().int(),
  amountLimit: z.number().positive().optional(),
  period: budgetPeriod.optional(),
};
