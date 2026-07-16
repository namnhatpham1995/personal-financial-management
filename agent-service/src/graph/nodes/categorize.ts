import type { AxiosInstance } from "axios";
import { AccountSchema, CategorySchema } from "../../schemas.js";
import type { LlmProvider } from "../../llm/provider.js";
import { LlmProviderError } from "../../llm/provider.js";
import type { RunState } from "../state.js";
import { z } from "zod";

/**
 * Maps extracted line items to the user's own categories/accounts. Categories and accounts are
 * fetched by the backend (already scoped to the initiating user) and passed to the LLM as
 * structured JSON tool input — never interpolated into a prompt string — so nothing extracted
 * from the receipt can influence which categories/accounts even enter the conversation.
 */
export function makeCategorizeNode(apiClient: AxiosInstance, llmProvider: LlmProvider) {
  return async function categorize(state: RunState): Promise<Partial<RunState>> {
    if (!state.extraction) {
      return { failureReason: "No extraction result to categorize.", retryable: false };
    }

    try {
      const [categoriesRes, accountsRes] = await Promise.all([
        apiClient.get("/categories"),
        apiClient.get("/accounts"),
      ]);
      const categories = z.array(CategorySchema).parse(categoriesRes.data);
      const accounts = z.array(AccountSchema).parse(accountsRes.data);

      const { proposals } = await llmProvider.categorize({
        extraction: state.extraction,
        categories,
        accounts,
      });

      return { categories, accounts, proposals };
    } catch (err) {
      if (err instanceof LlmProviderError) {
        return { failureReason: err.message, retryable: err.retryable };
      }
      return { failureReason: "Could not fetch categories/accounts or categorize items.", retryable: true };
    }
  };
}
