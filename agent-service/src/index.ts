import { PostgresSaver } from "@langchain/langgraph-checkpoint-postgres";
import { loadConfigOrExit } from "./config.js";
import { createServer } from "./server.js";
import { AnthropicLlmProvider } from "./llm/anthropic-provider.js";
import { StubLlmProvider } from "./llm/stub-provider.js";
import type { LlmProvider } from "./llm/provider.js";

async function main() {
  const config = loadConfigOrExit();

  const llmProvider: LlmProvider =
    config.llmProvider === "anthropic"
      ? new AnthropicLlmProvider(config.anthropicApiKey as string, config.anthropicModel)
      : new StubLlmProvider();

  const checkpointer = PostgresSaver.fromConnString(config.checkpointerDbUrl, {
    schema: "agent_checkpoints",
  });
  await checkpointer.setup();

  const app = createServer(config, llmProvider, checkpointer);
  app.listen(config.port, () => {
    // eslint-disable-next-line no-console
    console.log(`agent-service listening on :${config.port} (provider=${config.llmProvider})`);
  });
}

main().catch((err) => {
  // eslint-disable-next-line no-console
  console.error("agent-service failed to start:", err);
  process.exit(1);
});
