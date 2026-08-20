package com.company.paymentanalysis.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.paymentanalysis.chat.ChatConversationMemoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "server.port=18080", "smartbi.mock-enabled=true", "smartbi.mock-base-url=http://localhost:18080",
        "llm.mock-enabled=true"})
@AutoConfigureMockMvc
class AttributionControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ChatConversationMemoryService memoryService;

    @Test
    void returnsBoundedBranchAttributionForJulyDecline() throws Exception {
        mockMvc.perform(analyze("""
                {"metricId":"trans_rmb_amt_m","currentPeriod":"2026-07","comparisonPeriod":"2026-06",
                 "dimensionFilters":[],"maxDepth":2,"maxQueries":8,"topN":4,"maxBranches":2}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.overall.direction").value("DOWN"))
                .andExpect(jsonPath("$.overall.smartBiComparisonRate").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.queryCount").value(org.hamcrest.Matchers.lessThanOrEqualTo(8)))
                .andExpect(jsonPath("$.evidence.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.branches").isArray())
                .andExpect(jsonPath("$.branches.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.branches.length()").value(org.hamcrest.Matchers.lessThanOrEqualTo(2)))
                .andExpect(jsonPath("$.smartBiQueries.length()").value(org.hamcrest.Matchers.lessThanOrEqualTo(16)))
                .andExpect(jsonPath("$.reasoning[0].phase").value("PLAN"))
                .andExpect(jsonPath("$.reasoning[1].phase").value("REFLECT"))
                .andExpect(jsonPath("$.reasoning[1].branchActions").isArray());
    }

    @Test
    void savesTheCompletedAttributionWithoutExposingItToAQueryConversation() throws Exception {
        mockMvc.perform(analyze("""
                {"userId":"attribution-user","conversationId":"attribution-follow-up",
                 "metricId":"trans_rmb_amt_m","currentPeriod":"2026-07","comparisonPeriod":"2026-06",
                 "analysisPlan":{"levels":[{"level":1,"dimensionIds":["acq_ins_ch"]}],"continueExploration":false},
                 "dimensionFilters":[],"maxDepth":1,"maxQueries":4,"topN":4,"maxBranches":2}
                """))
                .andExpect(status().isOk());

        var artifacts = memoryService.snapshot("attribution-user", "attribution-follow-up",
                ChatConversationMemoryService.ConversationScope.ATTRIBUTION)
                .orElseThrow().artifacts();
        var artifact = artifacts.get(artifacts.size() - 1);
        assertThat(artifact.verifiedFacts()).isNotEmpty();
        assertThat(artifact.verifiedFacts()).allMatch(fact -> fact.source().startsWith("JAVA_")
                || "REQUEST_VALIDATION".equals(fact.source()));
        assertThat(artifact.modelNarrative()).contains("摘要=");

        mockMvc.perform(post("/api/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"attribution-user","sessionId":"attribution-follow-up",
                                 "message":"把刚才归因改写成一段业务汇报","context":
                                 {"metricIds":[],"dimensionIds":[],"dimensionFilters":[],"sorts":[]}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.derivedFromArtifactIds.length()").value(0))
                .andExpect(jsonPath("$.result").doesNotExist());
    }

    @Test
    void exposesAttributionOnlyMetadataAndLimits() throws Exception {
        mockMvc.perform(get("/api/attribution/metadata"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metrics.length()").value(24))
                .andExpect(jsonPath("$.dimensions.length()").value(71))
                .andExpect(jsonPath("$.dimensions[0].attributionEnabled").value(true))
                .andExpect(jsonPath("$.limits.defaultMaxDepth").value(2))
                .andExpect(jsonPath("$.limits.defaultMaxBranches").value(2))
                .andExpect(jsonPath("$.limits.hardMaxQueries").value(12));
    }

    @Test
    void rejectsInvalidMetricPeriodFilterAndLimits() throws Exception {
        mockMvc.perform(analyze("""
                {"metricId":"trans_cnt_hb","currentPeriod":"2026-06","comparisonPeriod":"2026-07",
                 "dimensionFilters":[{"dimensionId":"invented","operator":"EQUALS","values":["x"]}],"maxDepth":9}
                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(analyze("""
                {"metricId":"trans_cnt_m","currentPeriod":"2026-07","comparisonPeriod":"2026-06",
                 "analysisPlan":{"levels":[{"level":1,"dimensionIds":["invented"]}],"continueExploration":false},
                 "maxDepth":1,"maxQueries":8,"topN":4}
                """))
                .andExpect(status().isBadRequest());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder analyze(String body) {
        return post("/api/attribution/analyze").contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
