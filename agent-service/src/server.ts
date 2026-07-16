import express, { type Express } from "express";
import { z } from "zod";
import type { Config } from "./config.js";
import { createApiClient } from "./api-client.js";
import { buildGraph, type CompiledRunGraph } from "./graph/graph.js";
import { startRun, resumeRun } from "./graph/run-entrypoints.js";
import type { LlmProvider } from "./llm/provider.js";
import type { BaseCheckpointSaver } from "@langchain/langgraph";

const StartRunBodySchema = z.object({
  runId: z.number(),
  vaultDocumentId: z.string(),
  token: z.string(),
});

const ResumeBodySchema = z.object({
  token: z.string(),
  approved: z.boolean(),
  proposals: z.array(z.any()).optional(),
});

/** HTTP surface the backend calls to start/resume a run — see AgentServiceClient on the backend. */
export function createServer(
  config: Config,
  llmProvider: LlmProvider,
  checkpointer: BaseCheckpointSaver
): Express {
  const app = express();
  app.use(express.json());

  app.get("/health", (_req, res) => res.json({ status: "ok" }));

  app.post("/runs", (req, res) => {
    const parsed = StartRunBodySchema.safeParse(req.body);
    if (!parsed.success) {
      res.status(400).json({ error: parsed.error.message });
      return;
    }
    const { runId, vaultDocumentId, token } = parsed.data;
    const apiClient = createApiClient(config, token);
    const graph = compileGraph(config, llmProvider, apiClient, checkpointer);

    // Fire-and-forget: the backend already created the EXTRACTING run record synchronously
    // and does not block on graph completion (design.md: backend owns run state).
    void startRun(graph, apiClient, runId, vaultDocumentId, token);
    res.status(202).json({ accepted: true });
  });

  app.post("/runs/:runId/resume", (req, res) => {
    const runId = Number(req.params.runId);
    const parsed = ResumeBodySchema.safeParse(req.body);
    if (!Number.isFinite(runId) || !parsed.success) {
      res.status(400).json({ error: "Invalid resume request" });
      return;
    }
    const { token, approved, proposals } = parsed.data;
    const apiClient = createApiClient(config, token);
    const graph = compileGraph(config, llmProvider, apiClient, checkpointer);

    void resumeRun(graph, apiClient, runId, { approved, proposals });
    res.status(202).json({ accepted: true });
  });

  return app;
}

function compileGraph(
  config: Config,
  llmProvider: LlmProvider,
  apiClient: ReturnType<typeof createApiClient>,
  checkpointer: BaseCheckpointSaver
): CompiledRunGraph {
  return buildGraph(config, llmProvider, apiClient).compile({ checkpointer }) as CompiledRunGraph;
}
