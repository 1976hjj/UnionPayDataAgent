package com.company.paymentanalysis.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

    @Test
    void returnsBoundedBranchAttributionForJulyDecline() throws Exception {
        mockMvc.perform(analyze("""
                {"metricId":"trans_rmb_amt_m","currentPeriod":"2026-07","comparisonPeriod":"2026-06",
                 "dimensionFilters":[],"maxDepth":2,"maxQueries":8,"topN":4,"maxBranches":2}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.overall.direction").value("DOWN"))
                .andExpect(jsonPath("$.overall.smartBiComparisonRate").isNumber())
                .andExpect(jsonPath("$.queryCount").value(org.hamcrest.Matchers.lessThanOrEqualTo(8)))
                .andExpect(jsonPath("$.evidence.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.branches").isArray())
                .andExpect(jsonPath("$.branches.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.branches.length()").value(org.hamcrest.Matchers.lessThanOrEqualTo(2)))
                .andExpect(jsonPath("$.smartBiQueries.length()").value(org.hamcrest.Matchers.lessThanOrEqualTo(8)))
                .andExpect(jsonPath("$.reasoning[0].phase").value("PLAN"))
                .andExpect(jsonPath("$.reasoning[1].phase").value("REFLECT"))
                .andExpect(jsonPath("$.reasoning[1].branchActions").isArray());
    }

    @Test
    void maxDepthOneStopsAfterParallelFirstRound() throws Exception {
        mockMvc.perform(analyze("""
                {"metricId":"sh_jy_num_m","currentPeriod":"2026-03","comparisonPeriod":"2026-02",
                 "maxDepth":1,"maxQueries":8,"topN":4}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queryCount").value(4))
                .andExpect(jsonPath("$.stop.code").value("MAX_DEPTH"));
    }

    @Test
    void returnsEvidenceBackedPrimaryPathForAugustRecovery() throws Exception {
        mockMvc.perform(analyze("""
                {"metricId":"trans_rmb_amt_m","currentPeriod":"2026-08","comparisonPeriod":"2026-07",
                 "maxDepth":1,"maxQueries":8,"topN":4}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overall.direction").value("UP"))
                .andExpect(jsonPath("$.primaryPath.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.stop.code").value("MAX_DEPTH"));
    }

    @Test
    void maxQueryLimitIsEnforcedByTheWorkflow() throws Exception {
        mockMvc.perform(analyze("""
                {"metricId":"trans_rmb_amt_m","currentPeriod":"2026-07","comparisonPeriod":"2026-06",
                 "maxDepth":3,"maxQueries":2,"topN":4}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queryCount").value(2))
                .andExpect(jsonPath("$.evidence.length()").value(1))
                .andExpect(jsonPath("$.stop.code").value("MAX_QUERIES"));
    }

    @Test
    void readsYearOnYearDerivedMetricDirectlyFromSmartBi() throws Exception {
        mockMvc.perform(analyze("""
                {"metricId":"trans_rmb_amt_m","currentPeriod":"2026-07","comparisonPeriod":"2025-07",
                 "dimensionFilters":[{"dimensionId":"acq_mkt_ch","operator":"EQUALS","values":["欧洲市场"]}],
                 "maxDepth":1,"maxQueries":4,"topN":4}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overall.smartBiComparisonRate").isNumber())
                .andExpect(jsonPath("$.smartBiQueries[0].request.columns[0]").value("trans_rmb_amt_m"))
                .andExpect(jsonPath("$.smartBiQueries[0].request.columns[1]").value("trans_rmb_amt_tb"))
                .andExpect(jsonPath("$.smartBiQueries[0].request.filters[2].name").value("acq_mkt_ch"));
    }

    @Test
    void exposesAttributionOnlyMetadataAndLimits() throws Exception {
        mockMvc.perform(get("/api/attribution/metadata"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metrics.length()").value(8))
                .andExpect(jsonPath("$.dimensions.length()").value(15))
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
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder analyze(String body) {
        return post("/api/attribution/analyze").contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
