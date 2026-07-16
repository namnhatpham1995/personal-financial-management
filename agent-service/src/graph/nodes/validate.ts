import type { Proposal } from "../../schemas.js";
import type { RunState } from "../state.js";

const COMMON_CURRENCY_CODES = new Set([
  "USD", "EUR", "GBP", "JPY", "CAD", "AUD", "CHF", "CNY", "INR", "VND", "SGD", "HKD", "KRW",
]);

/**
 * Client-side mirror of the backend's authoritative deterministic validation (design.md D6) —
 * gives the graph a fast-failure signal before spending a round trip, but never the final word:
 * the backend re-runs the same checks when proposals are posted and again at commit time.
 */
export function validate(state: RunState): Partial<RunState> {
  const extraction = state.extraction;
  if (!extraction) {
    return { failureReason: "No extraction result to validate.", retryable: false };
  }

  const sum = state.proposals.reduce((acc, p) => (p.excluded ? acc : acc + Number(p.amount)), 0);
  const total = Number(extraction.total);
  const totalsMismatch = Number.isFinite(total) && Math.abs(sum - total) > 0.005;

  const today = new Date().toISOString().slice(0, 10);

  const validated: Proposal[] = state.proposals.map((p) => {
    const flags = new Set(p.flags);
    if (totalsMismatch) flags.add("totals-mismatch");
    if (p.date > today) flags.add("future-date");
    if (!COMMON_CURRENCY_CODES.has(p.currency)) flags.add("unrecognized-currency");
    return { ...p, flags: Array.from(flags) };
  });

  return { proposals: validated };
}
