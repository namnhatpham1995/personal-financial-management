import { describe, it, expect } from "vitest";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";
import { ProposalsSubmissionSchema } from "../schemas.js";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

/**
 * Contract test: this exact JSON fixture is also deserialized by the backend's
 * SubmitProposalsRequestContractTest (backend/src/test/java/com/fintrack/agent/web/dto/
 * SubmitProposalsRequestContractTest.java) into SubmitProposalsRequest/ProposalDto. If either
 * side's shape drifts from the other, one of the two suites breaks (design.md: "contract
 * fixtures... drift breaks CI on both sides").
 */
describe("proposal contract fixture", () => {
  it("validates against the agent-service's ProposalsSubmissionSchema", () => {
    const raw = readFileSync(path.join(__dirname, "fixtures/proposal-contract.json"), "utf8");
    const json = JSON.parse(raw);

    const result = ProposalsSubmissionSchema.safeParse(json);

    expect(result.success).toBe(true);
    if (result.success) {
      expect(result.data.proposals).toHaveLength(2);
      expect(result.data.proposals[1].categoryId).toBeNull();
      expect(result.data.proposals[1].flags).toContain("low-confidence");
    }
  });
});
