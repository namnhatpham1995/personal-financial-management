import { Command } from "@langchain/langgraph";
import type { AxiosInstance } from "axios";
import type { CompiledRunGraph, ReviewDecision } from "./graph.js";
import type { RunState } from "./state.js";
import { mapApiError } from "../api-client.js";

/** Starts extraction for a brand-new run. Reports failure to the backend if the graph errors
 *  out or finishes with a failureReason set (never leaves a run silently stuck). */
export async function startRun(
  graph: CompiledRunGraph,
  apiClient: AxiosInstance,
  runId: number,
  vaultDocumentId: string,
  agentToken: string
): Promise<void> {
  const config = { configurable: { thread_id: String(runId) } };
  const initialState: Partial<RunState> = { runId, vaultDocumentId, agentToken };

  try {
    const result = (await graph.invoke(initialState, config)) as RunState;
    await reportFailureIfAny(apiClient, runId, result);
  } catch (err) {
    await reportUnexpectedFailure(apiClient, runId, err);
  }
}

/** Resumes a paused run with the user's decision (approve/reject, possibly edited proposals). */
export async function resumeRun(
  graph: CompiledRunGraph,
  apiClient: AxiosInstance,
  runId: number,
  decision: ReviewDecision
): Promise<void> {
  const config = { configurable: { thread_id: String(runId) } };

  try {
    const result = (await graph.invoke(new Command({ resume: decision }), config)) as RunState;
    await reportFailureIfAny(apiClient, runId, result);
  } catch (err) {
    await reportUnexpectedFailure(apiClient, runId, err);
  }
}

async function reportFailureIfAny(apiClient: AxiosInstance, runId: number, state: RunState): Promise<void> {
  if (!state.failureReason) return;
  try {
    await apiClient.post(`/agent-runs/${runId}/fail`, {
      reason: state.failureReason,
      retryable: state.retryable ?? false,
    });
  } catch {
    // Best-effort: if the backend can't be reached to record the failure, the run stays in
    // its last known status; there is nothing further this process can safely do.
  }
}

async function reportUnexpectedFailure(apiClient: AxiosInstance, runId: number, err: unknown): Promise<void> {
  const mapped = mapApiError(err);
  try {
    await apiClient.post(`/agent-runs/${runId}/fail`, {
      reason: err instanceof Error ? err.message : "Unexpected agent-service error",
      retryable: mapped.retryable,
    });
  } catch {
    // Same best-effort caveat as above.
  }
}
