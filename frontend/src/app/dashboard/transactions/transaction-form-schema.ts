import { z } from "zod";
import type { Account } from "@/services/account-service";

export function todayIsoDate() {
  const now = new Date();
  const offsetMs = now.getTimezoneOffset() * 60000;
  return new Date(now.getTime() - offsetMs).toISOString().slice(0, 10);
}

export function buildSchema(accounts: Account[]) {
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
      if (data.accountId === data.transferAccountId) {
        ctx.addIssue({ code: z.ZodIssueCode.custom, path: ["transferAccountId"], message: "sameAccount" });
        return;
      }
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

export type TransactionFormValues = z.infer<ReturnType<typeof buildSchema>>;
