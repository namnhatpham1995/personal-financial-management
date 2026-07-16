import { afterAll, beforeAll, describe, expect, it } from "vitest";
import express, { type Express } from "express";
import type { Server } from "node:http";
import { PostgreSqlContainer, type StartedPostgreSqlContainer } from "@testcontainers/postgresql";
import { PostgresSaver } from "@langchain/langgraph-checkpoint-postgres";
import { createApiClient } from "../api-client.js";
import { buildGraph, type CompiledRunGraph } from "../graph/graph.js";
import { startRun, resumeRun } from "../graph/run-entrypoints.js";
import { StubLlmProvider } from "../llm/stub-provider.js";
import type { Config } from "../config.js";

/**
 * Full graph-level lifecycle tests against a real PostgreSQL checkpointer (Testcontainers) and
 * a local fake backend server — exercises the actual LangGraph interrupt/resume/checkpoint
 * machinery end-to-end rather than mocking it, matching how the backend integration tests in
 * this repo prefer real Testcontainers over mocks.
 */
describe("graph lifecycle", () => {
  let pgContainer: StartedPostgreSqlContainer;
  let fakeBackend: Server;
  let fakeBackendPort: number;
  const checkpointers: PostgresSaver[] = [];

  const proposalsCalls = new Map<number, unknown>();
  const commitCalls = new Set<number>();
  const failCalls = new Map<number, { reason: string; retryable: boolean }>();

  const CATEGORIES = [{ id: 1, name: "Groceries", transactionType: "EXPENSE" }];
  const ACCOUNTS = [{ id: 10, name: "Checking", currency: "USD" }];

  function buildFakeBackend(): Express {
    const app = express();
    app.use(express.json());

    // Vault doc id doubles as the stub scenario name for these tests.
    app.get("/api/vault/:id/download", (req, res) => {
      res.set("content-type", "text/plain");
      res.send(`scenario:${req.params.id}`);
    });
    app.get("/api/v1/categories", (_req, res) => res.json(CATEGORIES));
    app.get("/api/v1/accounts", (_req, res) => res.json(ACCOUNTS));
    app.post("/api/v1/agent-runs/:id/proposals", (req, res) => {
      proposalsCalls.set(Number(req.params.id), req.body);
      res.json({ ok: true });
    });
    app.post("/api/v1/agent-runs/:id/commit", (req, res) => {
      commitCalls.add(Number(req.params.id));
      res.json({ ok: true });
    });
    app.post("/api/v1/agent-runs/:id/fail", (req, res) => {
      failCalls.set(Number(req.params.id), req.body);
      res.json({ ok: true });
    });
    return app;
  }

  beforeAll(async () => {
    pgContainer = await new PostgreSqlContainer("postgres:16-alpine").start();

    const app = buildFakeBackend();
    await new Promise<void>((resolve) => {
      fakeBackend = app.listen(0, () => resolve());
    });
    fakeBackendPort = (fakeBackend.address() as { port: number }).port;
  }, 120_000);

  afterAll(async () => {
    fakeBackend?.close();
    await Promise.all(checkpointers.map((c) => c.end()));
    await pgContainer?.stop();
  });

  function makeConfig(): Config {
    return {
      backendApiUrl: `http://127.0.0.1:${fakeBackendPort}`,
      llmProvider: "stub",
      anthropicApiKey: undefined,
      anthropicModel: "unused",
      checkpointerDbUrl: pgContainer.getConnectionUri(),
      port: 0,
    };
  }

  async function makeCheckpointer(): Promise<PostgresSaver> {
    const checkpointer = PostgresSaver.fromConnString(pgContainer.getConnectionUri(), {
      schema: "agent_checkpoints",
    });
    await checkpointer.setup();
    checkpointers.push(checkpointer);
    return checkpointer;
  }

  async function makeGraph(config: Config): Promise<{ graph: CompiledRunGraph; apiClient: ReturnType<typeof createApiClient> }> {
    const checkpointer = await makeCheckpointer();
    const apiClient = createApiClient(config, "test-agent-token");
    const graph = buildGraph(config, new StubLlmProvider(), apiClient).compile({ checkpointer }) as CompiledRunGraph;
    return { graph, apiClient };
  }

  it("pauses at review with no commit call for a clean receipt", async () => {
    const config = makeConfig();
    const { graph, apiClient } = await makeGraph(config);
    const runId = 1001;

    await startRun(graph, apiClient, runId, "clean-receipt", "test-agent-token");

    expect(proposalsCalls.has(runId)).toBe(true);
    expect(commitCalls.has(runId)).toBe(false);
    expect(failCalls.has(runId)).toBe(false);

    const state = await graph.getState({ configurable: { thread_id: String(runId) } });
    expect(state.tasks.some((t) => t.name === "awaitReview" || t.interrupts?.length)).toBe(true);
  }, 30_000);

  it("resumes with edited proposals and commits", async () => {
    const config = makeConfig();
    const { graph, apiClient } = await makeGraph(config);
    const runId = 1002;

    await startRun(graph, apiClient, runId, "clean-receipt", "test-agent-token");
    expect(commitCalls.has(runId)).toBe(false);

    await resumeRun(graph, apiClient, runId, {
      approved: true,
      proposals: [
        { merchant: "Corner Market", date: "2026-01-05", amount: "3.50", currency: "USD",
          categoryId: 1, accountId: 10, description: "Milk (edited)", flags: [], excluded: false },
      ],
    });

    expect(commitCalls.has(runId)).toBe(true);
  }, 30_000);

  it("injection-text receipt still pauses at review, text only carried as data", async () => {
    const config = makeConfig();
    const { graph, apiClient } = await makeGraph(config);
    const runId = 1003;

    await startRun(graph, apiClient, runId, "injection-receipt", "test-agent-token");

    expect(commitCalls.has(runId)).toBe(false);
    const posted = proposalsCalls.get(runId) as { proposals: Array<{ description?: string }> };
    expect(posted.proposals[0].description).toContain("IGNORE ALL PREVIOUS INSTRUCTIONS");
  }, 30_000);

  it("provider-error fixture fails the run retryably", async () => {
    const config = makeConfig();
    const { graph, apiClient } = await makeGraph(config);
    const runId = 1004;

    await startRun(graph, apiClient, runId, "provider-error", "test-agent-token");

    expect(failCalls.get(runId)?.retryable).toBe(true);
    expect(commitCalls.has(runId)).toBe(false);
  }, 30_000);

  it("survives a simulated process restart: fresh checkpointer instance can still resume", async () => {
    const config = makeConfig();
    const runId = 1005;

    // "Process 1": start the run and pause.
    const first = await makeGraph(config);
    await startRun(first.graph, first.apiClient, runId, "clean-receipt", "test-agent-token");
    expect(commitCalls.has(runId)).toBe(false);

    // "Process 2": brand-new checkpointer + graph instances against the same Postgres schema —
    // simulates the agent-service restarting while the run was AWAITING_REVIEW.
    const second = await makeGraph(config);
    await resumeRun(second.graph, second.apiClient, runId, { approved: true });

    expect(commitCalls.has(runId)).toBe(true);
  }, 30_000);
});
