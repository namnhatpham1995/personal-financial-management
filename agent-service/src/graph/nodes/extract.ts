import axios from "axios";
import type { Config } from "../../config.js";
import type { LlmProvider } from "../../llm/provider.js";
import { LlmProviderError } from "../../llm/provider.js";
import type { RunState } from "../state.js";

/**
 * Fetches the receipt binary from the Vault (via the backend, using the run's agent token)
 * and asks the LLM provider to extract structured data. Failure here — unreachable binary or
 * a provider that can't parse the image — routes to run failure, never a partial proposal.
 */
export function makeExtractNode(config: Config, llmProvider: LlmProvider) {
  return async function extract(state: RunState): Promise<Partial<RunState>> {
    let receiptImage: Buffer;
    let contentType = "application/octet-stream";
    try {
      const response = await axios.get(
        `${config.backendApiUrl}/api/vault/${state.vaultDocumentId}/download`,
        {
          headers: { Authorization: `Bearer ${state.agentToken}` },
          responseType: "arraybuffer",
          timeout: 30_000,
        }
      );
      receiptImage = Buffer.from(response.data);
      const headerContentType = response.headers["content-type"];
      if (typeof headerContentType === "string") {
        contentType = headerContentType;
      }
    } catch (err) {
      return {
        failureReason: "Could not fetch the receipt binary from the vault.",
        retryable: true,
      };
    }

    try {
      const extraction = await llmProvider.extract(receiptImage, contentType);
      return { receiptImage, receiptContentType: contentType, extraction };
    } catch (err) {
      if (err instanceof LlmProviderError) {
        return { failureReason: err.message, retryable: err.retryable };
      }
      return { failureReason: "Extraction failed unexpectedly.", retryable: true };
    }
  };
}
