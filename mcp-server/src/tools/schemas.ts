import { z } from "zod";

export const isoDate = z.string().regex(/^\d{4}-\d{2}-\d{2}$/, "Expected an ISO date (YYYY-MM-DD)");
export const transactionType = z.enum(["INCOME", "EXPENSE", "TRANSFER"]);

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
  targetCurrency: z.string().length(3).optional(),
};
