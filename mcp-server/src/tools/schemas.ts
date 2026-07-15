import { z } from "zod";
import type { components } from "../generated/api-types.js";

/**
 * Builds a literal-string tuple that is checked, at compile time, to contain exactly the
 * members of Union — no more, no less. If the backend OpenAPI spec adds or removes an enum
 * value and the tuple below isn't updated to match, this fails `npm run type-check` instead
 * of silently drifting (see openspec/changes/mcp-openapi-contract-types).
 */
type ExactTuple<Union extends string, Tuple extends readonly Union[]> =
  Exclude<Union, Tuple[number]> extends never ? Tuple : never;

function exactTuple<Union extends string>() {
  return <const Tuple extends readonly Union[]>(tuple: ExactTuple<Union, Tuple>): Tuple => tuple;
}

type TransactionTypeUnion = NonNullable<components["schemas"]["CreateTransactionRequest"]["transactionType"]>;
type AccountTypeUnion = NonNullable<components["schemas"]["CreateAccountRequest"]["accountType"]>;
type BudgetPeriodUnion = NonNullable<components["schemas"]["CreateBudgetRequest"]["period"]>;

const transactionTypeValues = exactTuple<TransactionTypeUnion>()(["INCOME", "EXPENSE", "TRANSFER"] as const);
const accountTypeValues = exactTuple<AccountTypeUnion>()(
  ["CASH", "BANK", "CREDIT_CARD", "SAVINGS", "OTHER"] as const
);
const budgetPeriodValues = exactTuple<BudgetPeriodUnion>()(["MONTHLY", "YEARLY"] as const);

export const isoDate = z.string().regex(/^\d{4}-\d{2}-\d{2}$/, "Expected an ISO date (YYYY-MM-DD)");
export const transactionType = z.enum(transactionTypeValues);
export const accountType = z.enum(accountTypeValues);
export const budgetPeriod = z.enum(budgetPeriodValues);
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
  /**
   * Required for TRANSFER between accounts with different currencies, denominated in the
   * destination account's currency; forbidden otherwise. The backend rejects a mismatch with
   * a 400 naming this field — retry with it set to the amount actually received.
   */
  destinationAmount: z.number().positive().optional(),
  categoryId: z.number().int().optional(),
  note: z.string().max(2000).optional(),
};

export const updateTransactionShape = {
  id: z.number().int(),
  amount: z.number().positive().optional(),
  /** Must be supplied together with amount when updating a cross-currency transfer. */
  destinationAmount: z.number().positive().optional(),
  transactionDate: isoDate.optional(),
  categoryId: z.number().int().optional(),
  note: z.string().max(2000).optional(),
};

export const createTransactionsBatchShape = {
  transactions: z.array(z.object({
    transactionType,
    amount: z.number().positive(),
    transactionDate: isoDate,
    accountId: z.number().int(),
    transferAccountId: z.number().int().optional(),
    destinationAmount: z.number().positive().optional(),
    categoryId: z.number().int().optional(),
    note: z.string().max(2000).optional(),
    importDedupKey: z.string().min(1).max(255).optional(),
  }).strict()).min(1).max(100),
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

export const budgetHistoryShape = {
  ...dateRangeShape,
  currency: currencyCode.optional(),
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

// ── Compile-time contract pinning ───────────────────────────────────────────
// Unused type aliases, evaluated only by the compiler: each fails `npm run type-check` if a
// hand-written shape above stops matching the backend request DTO it corresponds to.
type AssertAssignable<Expected, _Actual extends Expected> = true;

type _CheckCreateTransaction = AssertAssignable<
  components["schemas"]["CreateTransactionRequest"],
  z.infer<z.ZodObject<typeof createTransactionShape>>
>;
type _CheckUpdateTransaction = AssertAssignable<
  components["schemas"]["UpdateTransactionRequest"],
  z.infer<z.ZodObject<typeof updateTransactionShape>>
>;
type _CheckBatchTransactionRow = AssertAssignable<
  components["schemas"]["CreateTransactionRequest"],
  z.infer<typeof createTransactionsBatchShape.transactions>[number]
>;
type _CheckCreateAccount = AssertAssignable<
  components["schemas"]["CreateAccountRequest"],
  z.infer<z.ZodObject<typeof createAccountShape>>
>;
type _CheckUpdateAccount = AssertAssignable<
  components["schemas"]["UpdateAccountRequest"],
  z.infer<z.ZodObject<typeof updateAccountShape>>
>;
type _CheckCreateBudget = AssertAssignable<
  components["schemas"]["CreateBudgetRequest"],
  z.infer<z.ZodObject<typeof createBudgetShape>>
>;
type _CheckUpdateBudget = AssertAssignable<
  components["schemas"]["UpdateBudgetRequest"],
  z.infer<z.ZodObject<typeof updateBudgetShape>>
>;
