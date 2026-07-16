import type { AxiosInstance } from "axios";
import { mapApiError } from "../../api-client.js";
import type { RunState } from "../state.js";

/**
 * Runs only after the human review interrupt resumes with approval. Calls the backend's
 * agent-token commit endpoint — idempotent and status-guarded on the backend side (design.md
 * D5), so this is safe to call even if the user's decision endpoint already committed.
 */
export function makeCommitNode(apiClient: AxiosInstance) {
  return async function commit(state: RunState): Promise<Partial<RunState>> {
    if (state.approved === false) {
      // Rejected — nothing to commit, the backend already closed the run via the decision endpoint.
      return {};
    }
    try {
      await apiClient.post(`/agent-runs/${state.runId}/commit`, {});
      return {};
    } catch (err) {
      const mapped = mapApiError(err);
      return { failureReason: mapped.message, retryable: mapped.retryable };
    }
  };
}
