import { StateGraph, START, END, interrupt, type CompiledStateGraph } from "@langchain/langgraph";
import type { AxiosInstance } from "axios";
import type { Config } from "../config.js";
import type { LlmProvider } from "../llm/provider.js";
import type { Proposal } from "../schemas.js";
import { makeExtractNode } from "./nodes/extract.js";
import { makeCategorizeNode } from "./nodes/categorize.js";
import { validate } from "./nodes/validate.js";
import { makeSubmitProposalsNode } from "./nodes/submit-proposals.js";
import { makeCommitNode } from "./nodes/commit.js";
import { RunStateAnnotation, type RunState } from "./state.js";

/** The resume payload the decision endpoint forwards — the user's approve/reject + edits. */
export interface ReviewDecision {
  approved: boolean;
  proposals?: Proposal[];
}

/**
 * Pauses the graph in AWAITING_REVIEW. `interrupt()` throws internally to suspend execution at
 * this exact point — LangGraph's checkpointer persists state up to here, so a restart resumes
 * cleanly (design.md: "run pauses before any write" / "paused run survives service restart").
 */
function awaitReview(_state: RunState): Partial<RunState> {
  const decision = interrupt<undefined, ReviewDecision>(undefined);
  return { approved: decision.approved, decidedProposals: decision.proposals };
}

function hasFailed(state: RunState): boolean {
  return Boolean(state.failureReason);
}

export function buildGraph(config: Config, llmProvider: LlmProvider, apiClient: AxiosInstance) {
  const extract = makeExtractNode(config, llmProvider);
  const categorize = makeCategorizeNode(apiClient, llmProvider);
  const submitProposals = makeSubmitProposalsNode(apiClient);
  const commit = makeCommitNode(apiClient);

  const graph = new StateGraph(RunStateAnnotation)
    .addNode("extract", extract)
    .addNode("categorize", categorize)
    .addNode("validate", validate)
    .addNode("submitProposals", submitProposals)
    .addNode("awaitReview", awaitReview)
    .addNode("commit", commit)
    .addEdge(START, "extract")
    .addConditionalEdges("extract", (s) => (hasFailed(s) ? END : "categorize"))
    .addConditionalEdges("categorize", (s) => (hasFailed(s) ? END : "validate"))
    .addConditionalEdges("validate", (s) => (hasFailed(s) ? END : "submitProposals"))
    .addConditionalEdges("submitProposals", (s) => (hasFailed(s) ? END : "awaitReview"))
    .addConditionalEdges("awaitReview", (s) => (s.approved ? "commit" : END))
    .addEdge("commit", END);

  return graph;
}

export type UncompiledRunGraph = ReturnType<typeof buildGraph>;
export type CompiledRunGraph = CompiledStateGraph<RunState, Partial<RunState>, string>;
