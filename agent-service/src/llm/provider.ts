import type { Account, Category, ExtractionResult, Proposal } from "../schemas.js";

export class LlmProviderError extends Error {
  /** True when the failure is transient (outage/rate limit) rather than a bad response. */
  readonly retryable: boolean;

  constructor(message: string, retryable: boolean) {
    super(message);
    this.name = "LlmProviderError";
    this.retryable = retryable;
  }
}

export interface CategorizeInput {
  extraction: ExtractionResult;
  categories: Category[];
  accounts: Account[];
}

export interface CategorizeOutput {
  proposals: Proposal[];
}

/**
 * Behind this interface: `anthropic` (vision-capable model, real receipt images) and `stub`
 * (fixture-driven, selected via LLM_PROVIDER=stub) so every graph node and the full run
 * lifecycle is testable without a live model call (design.md D7).
 */
export interface LlmProvider {
  extract(receiptImage: Buffer, contentType: string): Promise<ExtractionResult>;
  categorize(input: CategorizeInput): Promise<CategorizeOutput>;
}
