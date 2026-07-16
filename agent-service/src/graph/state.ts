import { Annotation } from "@langchain/langgraph";
import type { Account, Category, ExtractionResult, Proposal } from "../schemas.js";

/**
 * Graph state for one ingestion run. Carries only what the graph needs to execute — the
 * backend (not this state) is the system of record for run status (design.md D2); this state
 * is checkpointed so a paused run survives an agent-service restart.
 */
export const RunStateAnnotation = Annotation.Root({
  runId: Annotation<number>(),
  vaultDocumentId: Annotation<string>(),
  agentToken: Annotation<string>(),

  receiptImage: Annotation<Buffer | undefined>(),
  receiptContentType: Annotation<string | undefined>(),

  categories: Annotation<Category[]>({ default: () => [], reducer: (_prev, next) => next }),
  accounts: Annotation<Account[]>({ default: () => [], reducer: (_prev, next) => next }),

  extraction: Annotation<ExtractionResult | undefined>(),
  proposals: Annotation<Proposal[]>({ default: () => [], reducer: (_prev, next) => next }),

  /** Set by the review interrupt's resume value — the user's (possibly edited) proposals. */
  decidedProposals: Annotation<Proposal[] | undefined>(),
  approved: Annotation<boolean | undefined>(),

  failureReason: Annotation<string | undefined>(),
  retryable: Annotation<boolean | undefined>(),
});

export type RunState = typeof RunStateAnnotation.State;
