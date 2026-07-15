/**
 * Tests for transaction and budget form Zod validation schemas (task 4.4).
 * Validates that required/invalid input is rejected and valid input passes.
 */
import { describe, it, expect } from "vitest";
import { z } from "zod";

// ── Transaction schema (mirrors transaction-form.tsx's buildSchema) ───────────

interface MirroredAccount {
  id: number;
  currency: string;
}

function buildTransactionSchema(accounts: MirroredAccount[]) {
  return z
    .object({
      accountId: z.coerce.number(),
      transactionType: z.enum(["INCOME", "EXPENSE", "TRANSFER"]),
      amount: z.string().min(1),
      transactionDate: z.string().min(1),
      note: z.string().optional(),
      transferAccountId: z.coerce.number().optional(),
      destinationAmount: z.string().optional(),
      categoryId: z.preprocess(
        (v) => (v === "" || v === undefined || v === null ? undefined : Number(v)),
        z.number().positive().optional()
      ),
    })
    .superRefine((data, ctx) => {
      if (data.transactionType !== "TRANSFER" || !data.transferAccountId) return;
      const source = accounts.find((a) => a.id === data.accountId);
      const dest = accounts.find((a) => a.id === data.transferAccountId);
      if (!source || !dest) return;
      const crossCurrency = source.currency !== dest.currency;
      const hasDestinationAmount = Boolean(data.destinationAmount && data.destinationAmount.trim() !== "");
      if (crossCurrency && !hasDestinationAmount) {
        ctx.addIssue({ code: z.ZodIssueCode.custom, path: ["destinationAmount"], message: "required" });
      }
      if (!crossCurrency && hasDestinationAmount) {
        ctx.addIssue({ code: z.ZodIssueCode.custom, path: ["destinationAmount"], message: "notAllowed" });
      }
    });
}

const noAccounts: MirroredAccount[] = [];
const transactionSchema = buildTransactionSchema(noAccounts);

describe("transactionSchema", () => {
  const valid = {
    accountId: 1,
    transactionType: "INCOME" as const,
    amount: "50.00",
    transactionDate: "2026-06-01",
  };

  it("accepts a valid INCOME transaction", () => {
    expect(transactionSchema.safeParse(valid).success).toBe(true);
  });

  it("rejects missing accountId", () => {
    const { accountId: _, ...rest } = valid;
    const result = transactionSchema.safeParse(rest);
    expect(result.success).toBe(false);
  });

  it("rejects empty amount string", () => {
    const result = transactionSchema.safeParse({ ...valid, amount: "" });
    expect(result.success).toBe(false);
  });

  it("rejects empty transactionDate", () => {
    const result = transactionSchema.safeParse({ ...valid, transactionDate: "" });
    expect(result.success).toBe(false);
  });

  it("rejects an invalid transactionType", () => {
    const result = transactionSchema.safeParse({ ...valid, transactionType: "GIFT" });
    expect(result.success).toBe(false);
  });

  it("accepts a TRANSFER with transferAccountId", () => {
    const transfer = { ...valid, transactionType: "TRANSFER" as const, transferAccountId: 2 };
    expect(transactionSchema.safeParse(transfer).success).toBe(true);
  });

  it("treats empty-string categoryId as undefined (not a parse error)", () => {
    const result = transactionSchema.safeParse({ ...valid, categoryId: "" });
    expect(result.success).toBe(true);
    if (result.success) expect(result.data.categoryId).toBeUndefined();
  });
});

describe("transactionSchema cross-currency transfer validation", () => {
  const eurToVnd: MirroredAccount[] = [
    { id: 1, currency: "EUR" },
    { id: 2, currency: "VND" },
  ];
  const bothUsd: MirroredAccount[] = [
    { id: 1, currency: "USD" },
    { id: 2, currency: "USD" },
  ];
  const base = {
    accountId: 1,
    transactionType: "TRANSFER" as const,
    amount: "500.00",
    transactionDate: "2026-06-01",
    transferAccountId: 2,
  };

  it("rejects a cross-currency transfer without destinationAmount", () => {
    const result = buildTransactionSchema(eurToVnd).safeParse(base);
    expect(result.success).toBe(false);
    if (!result.success) {
      expect(result.error.issues[0].path).toEqual(["destinationAmount"]);
      expect(result.error.issues[0].message).toBe("required");
    }
  });

  it("accepts a cross-currency transfer with destinationAmount", () => {
    const result = buildTransactionSchema(eurToVnd).safeParse({ ...base, destinationAmount: "14600000.00" });
    expect(result.success).toBe(true);
  });

  it("rejects destinationAmount on a same-currency transfer", () => {
    const result = buildTransactionSchema(bothUsd).safeParse({ ...base, destinationAmount: "500.00" });
    expect(result.success).toBe(false);
    if (!result.success) {
      expect(result.error.issues[0].path).toEqual(["destinationAmount"]);
      expect(result.error.issues[0].message).toBe("notAllowed");
    }
  });

  it("accepts a same-currency transfer without destinationAmount", () => {
    const result = buildTransactionSchema(bothUsd).safeParse(base);
    expect(result.success).toBe(true);
  });
});

// ── Budget limit schema (mirrors budget-limit-form.tsx) ───────────────────────

const budgetLimitSchema = z.object({
  amount: z.string().min(1, "Enter an amount"),
  period: z.enum(["MONTHLY", "YEARLY"]),
});

describe("budgetLimitSchema", () => {
  const valid = { amount: "500", period: "MONTHLY" as const };

  it("accepts a valid MONTHLY budget", () => {
    expect(budgetLimitSchema.safeParse(valid).success).toBe(true);
  });

  it("accepts a valid YEARLY budget", () => {
    expect(budgetLimitSchema.safeParse({ ...valid, period: "YEARLY" }).success).toBe(true);
  });

  it("rejects empty amount", () => {
    const result = budgetLimitSchema.safeParse({ ...valid, amount: "" });
    expect(result.success).toBe(false);
    if (!result.success) {
      expect(result.error.issues[0].message).toBe("Enter an amount");
    }
  });

  it("rejects an invalid period", () => {
    const result = budgetLimitSchema.safeParse({ ...valid, period: "WEEKLY" });
    expect(result.success).toBe(false);
  });

  it("rejects missing period", () => {
    const { period: _, ...rest } = valid;
    expect(budgetLimitSchema.safeParse(rest).success).toBe(false);
  });
});
