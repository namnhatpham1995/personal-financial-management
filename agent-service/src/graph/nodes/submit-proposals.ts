import type { AxiosInstance } from "axios";
import { mapApiError } from "../../api-client.js";
import type { RunState } from "../state.js";

/**
 * Posts extraction + validated proposals to the backend's authoritative validation endpoint
 * (POST /agent-runs/{id}/proposals). The backend re-validates from scratch and is the source
 * of truth for the flags actually shown to the reviewer — this node's job is just delivery.
 */
export function makeSubmitProposalsNode(apiClient: AxiosInstance) {
  return async function submitProposals(state: RunState): Promise<Partial<RunState>> {
    if (!state.extraction) {
      return { failureReason: "No extraction result to submit.", retryable: false };
    }
    try {
      await apiClient.post(`/agent-runs/${state.runId}/proposals`, {
        extraction: state.extraction,
        proposals: state.proposals,
      });
      return {};
    } catch (err) {
      const mapped = mapApiError(err);
      return { failureReason: mapped.message, retryable: mapped.retryable };
    }
  };
}
