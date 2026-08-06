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

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
            "server.port=18080",
            "smartbi.mock-enabled=true",
            "smartbi.mock-base-url=http://localhost:18080",
            "llm.mock-enabled=true"
        })
@AutoConfigureMockMvc
class AttributionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void dynamicallyFindsInstitutionAThenUnitedKingdomForJulyDecline() throws Exception {
        mockMvc.perform(post("/api/attribution/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "metricId":"trans_rmb_amt_m",
                                  "currentPeriod":"2026-07",
                                  "comparisonPeriod":"2026-06",
                                  "dimensionFilters":[],
                                  "maxDepth":2,
                                  "maxQueries":8,
                                  "topN":4
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.overall.direction").value("DOWN"))
                .andExpect(jsonPath("$.overall.smartBiComparisonRate").isNumber())
                .andExpect(jsonPath("$.queryCount").value(5))
                .andExpect(jsonPath("$.evidence.length()").value(4))
                .andExpect(jsonPath("$.primaryPath.length()").value(2))
                .andExpect(jsonPath("$.primaryPath[0].dimensionId").value("acq_ins_ch"))
                .andExpect(jsonPath("$.primaryPath[0].memberValue").value("收单机构A"))
                .andExpect(jsonPath("$.primaryPath[1].dimensionId").value("iss_sc_ch"))
                .andExpect(jsonPath("$.primaryPath[1].memberValue").value("英国"))
                .andExpect(jsonPath("$.stop.code").value("MAX_DEPTH"))
                .andExpect(jsonPath("$.smartBiQueries.length()").value(5))
                .andExpect(jsonPath("$.reasoning[0].phase").value("PLAN"))
                .andExpect(jsonPath("$.reasoning[1].phase").value("REASON"))
                .andExpect(jsonPath("$.reasoning[2].phase").value("REPORT"));
    }

    @Test
    void maxDepthOneStopsAfterParallelFirstRound() throws Exception {
        mockMvc.perform(post("/api/attribution/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "metricId":"sh_jy_num_m",
                                  "currentPeriod":"2026-03",
                                  "comparisonPeriod":"2026-02",
                                  "maxDepth":1,
                                  "maxQueries":8,
                                  "topN":4
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queryCount").value(4))
                .andExpect(jsonPath("$.primaryPath[0].memberValue").value("收单机构C"))
                .andExpect(jsonPath("$.stop.code").value("MAX_DEPTH"));
    }

    @Test
    void findsInstitutionAAsThePrimaryDriverForAugustRecovery() throws Exception {
        mockMvc.perform(post("/api/attribution/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "metricId":"trans_rmb_amt_m",
                                  "currentPeriod":"2026-08",
                                  "comparisonPeriod":"2026-07",
                                  "maxDepth":1,
                                  "maxQueries":8,
                                  "topN":4
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overall.direction").value("UP"))
                .andExpect(jsonPath("$.primaryPath[0].dimensionId").value("acq_ins_ch"))
                .andExpect(jsonPath("$.primaryPath[0].memberValue").value("收单机构A"))
                .andExpect(jsonPath("$.stop.code").value("MAX_DEPTH"));
    }

    @Test
    void maxQueryLimitIsEnforcedByTheWorkflow() throws Exception {
        mockMvc.perform(post("/api/attribution/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "metricId":"trans_rmb_amt_m",
                                  "currentPeriod":"2026-07",
                                  "comparisonPeriod":"2026-06",
                                  "maxDepth":3,
                                  "maxQueries":2,
                                  "topN":4
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queryCount").value(2))
                .andExpect(jsonPath("$.evidence.length()").value(1))
                .andExpect(jsonPath("$.stop.code").value("MAX_QUERIES"));
    }

    @Test
    void readsYearOnYearDerivedMetricDirectlyFromSmartBi() throws Exception {
        mockMvc.perform(post("/api/attribution/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "metricId":"trans_rmb_amt_m",
                                  "currentPeriod":"2026-07",
                                  "comparisonPeriod":"2025-07",
                                  "dimensionFilters":[
                                    {"dimensionId":"acq_mkt_ch","operator":"EQUALS","values":["欧洲市场"]}
                                  ],
                                  "maxDepth":1,
                                  "maxQueries":4,
                                  "topN":4
                                }
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
                .andExpect(jsonPath("$.limits.hardMaxQueries").value(12));
    }

    @Test
    void rejectsInvalidMetricPeriodFilterAndLimits() throws Exception {
        mockMvc.perform(post("/api/attribution/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "metricId":"trans_cnt_hb",
                                  "currentPeriod":"2026-06",
                                  "comparisonPeriod":"2026-07",
                                  "dimensionFilters":[
                                    {"dimensionId":"invented","operator":"EQUALS","values":["x"]}
                                  ],
                                  "maxDepth":9
                                }
                                """))
                .andExpect(status().isBadRequest());
    }
}
