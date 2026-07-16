import { LlmProviderError, type CategorizeInput, type CategorizeOutput, type LlmProvider } from "./provider.js";
import { ExtractionResultSchema, type ExtractionResult, type Proposal } from "../schemas.js";
import cleanReceipt from "./fixtures/clean-receipt.json" with { type: "json" };
import totalsMismatch from "./fixtures/totals-mismatch.json" with { type: "json" };
import unknownCategories from "./fixtures/unknown-categories.json" with { type: "json" };
import unparseable from "./fixtures/unparseable.json" with { type: "json" };
import injectionReceipt from "./fixtures/injection-receipt.json" with { type: "json" };
import providerError from "./fixtures/provider-error.json" with { type: "json" };

// Statically imported (not read from disk at runtime): `tsc` only compiles .ts files, so a
// fs.readFileSync against a path under dist/ would silently find nothing in the packaged
// build even though it works under tsx/vitest, which run against src/ directly.
const FIXTURES: Record<string, Record<string, unknown>> = {
  "clean-receipt": cleanReceipt,
  "totals-mismatch": totalsMismatch,
  "unknown-categories": unknownCategories,
  "unparseable": unparseable,
  "injection-receipt": injectionReceipt,
  "provider-error": providerError,
};

/**
 * Deterministic, fixture-driven LlmProvider used by every test and by CI (design.md D7) — no
 * network call, no live model dependence. Selects a fixture by reading a "scenario:<name>"
 * marker out of the "receipt image" buffer, so callers (tests, and eventually a stub upload
 * path) choose behavior explicitly rather than the stub trying to interpret real image bytes.
 */
export class StubLlmProvider implements LlmProvider {
  async extract(receiptImage: Buffer): Promise<ExtractionResult> {
    const scenario = parseScenario(receiptImage);
    const raw = loadFixture(scenario);

    if (raw.simulateError) {
      throw new LlmProviderError(String(raw.message ?? "Simulated provider error"), true);
    }

    const parsed = ExtractionResultSchema.safeParse(raw);
    if (!parsed.success || !parsed.data.merchant || !parsed.data.date || !parsed.data.total) {
      throw new LlmProviderError("Could not extract required fields from the receipt.", false);
    }
    return parsed.data;
  }

  async categorize(input: CategorizeInput): Promise<CategorizeOutput> {
    const { extraction, categories, accounts } = input;
    const defaultAccount = accounts.find((a) => a.currency === extraction.currency) ?? accounts[0];

    const proposals: Proposal[] = extraction.lineItems.map((item) => {
      const match = categories.find((c) =>
        item.description.toLowerCase().includes(c.name.toLowerCase())
        || c.name.toLowerCase().includes(item.description.toLowerCase())
      );
      return {
        merchant: extraction.merchant,
        date: extraction.date,
        amount: item.amount,
        currency: extraction.currency,
        categoryId: match?.id ?? null,
        accountId: defaultAccount?.id ?? null,
        description: item.description,
        flags: match ? [] : ["low-confidence"],
        excluded: false,
      };
    });

    return { proposals };
  }
}

function parseScenario(receiptImage: Buffer): string {
  const text = receiptImage.toString("utf8");
  const match = /^scenario:([a-z0-9-]+)/.exec(text);
  return match ? match[1] : "clean-receipt";
}

function loadFixture(scenario: string): Record<string, unknown> {
  const fixture = FIXTURES[scenario];
  if (!fixture) {
    throw new LlmProviderError(`Unknown stub scenario: ${scenario}`, false);
  }
  return fixture;
}
