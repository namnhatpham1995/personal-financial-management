package com.fintrack.agent.web.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test: this exact JSON fixture is also validated by the agent-service against
 * ProposalsSubmissionSchema (agent-service/src/test/contract.test.ts). If either side's shape
 * drifts from the other, one of the two suites breaks (design.md: "contract fixtures... drift
 * breaks CI on both sides").
 */
class SubmitProposalsRequestContractTest {

    @Test
    void proposalContractFixture_deserializesIntoSubmitProposalsRequest() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        try (InputStream in = getClass().getResourceAsStream("/agent/proposal-contract.json")) {
            SubmitProposalsRequest request = objectMapper.readValue(in, SubmitProposalsRequest.class);

            assertThat(request.extraction().get("merchant")).isEqualTo("Corner Market");
            assertThat(request.proposals()).hasSize(2);

            ProposalDto flagged = request.proposals().get(1);
            assertThat(flagged.categoryId()).isNull();
            assertThat(flagged.flags()).contains("low-confidence");
            assertThat(flagged.amount()).isEqualByComparingTo("4.00");
        }
    }
}
