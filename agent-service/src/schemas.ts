import { z } from "zod";

/**
 * LLM vision extraction result. Field shape mirrors the backend's `extraction` JSONB column
 * (AgentRun.extraction) — kept in sync via the contract fixtures in src/llm/fixtures/.
 */
export const LineItemSchema = z.object({
  description: z.string(),
  quantity: z.number().optional(),
  amount: z.string(), // decimal-as-string — never a float, matches backend DECIMAL(19,4) handling
});
export type LineItem = z.infer<typeof LineItemSchema>;

export const ExtractionResultSchema = z.object({
  merchant: z.string(),
  date: z.string(), // ISO yyyy-MM-dd
  currency: z.string(),
  lineItems: z.array(LineItemSchema),
  total: z.string(),
});
export type ExtractionResult = z.infer<typeof ExtractionResultSchema>;

/**
 * A single proposed transaction. Mirrors backend ProposalDto exactly — the same JSON fixtures
 * are asserted against this schema here and against ProposalDto deserialization in backend
 * tests (design.md: contract tests, task 2.9/1.8), so drift between the two breaks CI on both
 * sides rather than silently diverging.
 */
export const ProposalSchema = z.object({
  merchant: z.string(),
  date: z.string(),
  amount: z.string(),
  currency: z.string(),
  categoryId: z.number().nullable().optional(),
  accountId: z.number().nullable().optional(),
  description: z.string().optional(),
  flags: z.array(z.string()).default([]),
  excluded: z.boolean().default(false),
});
export type Proposal = z.infer<typeof ProposalSchema>;

export const ProposalsSubmissionSchema = z.object({
  extraction: ExtractionResultSchema,
  proposals: z.array(ProposalSchema),
});
export type ProposalsSubmission = z.infer<typeof ProposalsSubmissionSchema>;

/** A user's category/account, as returned by the backend's read-only list endpoints. */
export const CategorySchema = z.object({
  id: z.number(),
  name: z.string(),
  transactionType: z.string(),
});
export type Category = z.infer<typeof CategorySchema>;

export const AccountSchema = z.object({
  id: z.number(),
  name: z.string(),
  currency: z.string(),
});
export type Account = z.infer<typeof AccountSchema>;
