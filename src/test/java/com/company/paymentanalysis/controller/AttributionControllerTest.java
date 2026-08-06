package com.company.paymentanalysis.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {"server.port=18080", "smartbi.base-url=http://localhost:18080", "llm.mock-enabled=true"})
@AutoConfigureMockMvc
class AttributionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @Disabled("Requires SmartBI SDK runtime dependencies supplied by the company environment")
    void returnsThreeParallelLevel2ResultsWithFiveQueries() throws Exception {
        mockMvc.perform(post("/api/attribution/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "metricCode": "rmbAmount",
                                  "currentPeriod": "2026-07",
                                  "comparisonType": "monthOnMonth",
                                  "level1DimensionCode": "acquiringRegion",
                                  "level2DimensionCodes": [
                                    "issuingRegion",
                                    "acquiringInstitution",
                                    "transactionMedia"
                                  ],
                                  "businessScope": "foreignCardDomestic"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalQueryCount").value(5))
                .andExpect(jsonPath("$.periodsCombinedInSingleQuery").value(true))
                .andExpect(jsonPath("$.executionEngine").value("LangGraph4j → Mock LLM → Mock SmartBI"))
                .andExpect(jsonPath("$.workflowSteps.length()").value(6))
                .andExpect(jsonPath("$.workflowSteps[0].node").value("translateChineseValues"))
                .andExpect(jsonPath("$.workflowSteps[4].node").value("executeParallelLevel2Queries"))
                .andExpect(jsonPath("$.workflowSteps[5].status").value("COMPLETED"))
                .andExpect(jsonPath("$.llmMessage.role").value("assistant"))
                .andExpect(jsonPath("$.llmMessage.content").isNotEmpty())
                .andExpect(jsonPath("$.llmMessage.requestMessages.length()").value(2))
                .andExpect(jsonPath("$.llmMessage.requestMessages[0].role").value("system"))
                .andExpect(jsonPath("$.llmMessage.requestMessages[1].role").value("user"))
                .andExpect(jsonPath("$.level1Driver.memberName").value("华东地区"))
                .andExpect(jsonPath("$.level2Results.length()").value(3))
                .andExpect(jsonPath("$.level2Results.issuingRegion.dimensionName").value("发卡地区"))
                .andExpect(jsonPath("$.level2Results.issuingRegion.members[0].currentValue").value("¥52,640,000"))
                .andExpect(jsonPath("$.level2Results.issuingRegion.members[0].comparisonValue").value("¥46,130,000"))
                .andExpect(jsonPath("$.level2Results.issuingRegion.members[2].direction").value("DOWN"))
                .andExpect(jsonPath("$.level2Results.issuingRegion.members[2].contributionRate").value(-9.9))
                .andExpect(jsonPath("$.smartBiQueries.length()").value(5))
                .andExpect(jsonPath("$.smartBiQueries[0].stage").value("overall"))
                .andExpect(jsonPath("$.smartBiQueries[0].request.dataSetId")
                        .value("Iff8080810196b306b3067b2a0196b3067b2a0000"))
                .andExpect(jsonPath("$.smartBiQueries[0].request.columns[0]").value("acpt_trans_rmb_amt_m"))
                .andExpect(jsonPath("$.smartBiQueries[0].request.columns[1]").value("acpt_trans_rmb_amt_hb"))
                .andExpect(jsonPath("$.smartBiQueries[0].sqlPreview")
                        .value(org.hamcrest.Matchers.containsString("SUM(acpt_trans_rmb_amt_m)")))
                .andExpect(jsonPath("$.smartBiQueries[2].request.rows[1]").value("iss_mkt_ch"))
                .andExpect(jsonPath("$.smartBiQueries[2].request.relationNode.relation").value("AND"))
                .andExpect(jsonPath("$.reportNotice").value("多个二级维度是同一批数据的不同观察角度，各维度贡献不能相互累加。"));
    }

    @Test
    void rejectsLevel1DimensionRepeatedAtLevel2() throws Exception {
        mockMvc.perform(post("/api/attribution/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "metricCode": "rmbAmount",
                                  "currentPeriod": "2026-07",
                                  "comparisonType": "monthOnMonth",
                                  "level1DimensionCode": "acquiringRegion",
                                  "level2DimensionCodes": ["acquiringRegion"],
                                  "businessScope": ""
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsEmptyLevel2Dimensions() throws Exception {
        mockMvc.perform(post("/api/attribution/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "metricCode": "rmbAmount",
                                  "currentPeriod": "2026-07",
                                  "comparisonType": "monthOnMonth",
                                  "level1DimensionCode": "acquiringRegion",
                                  "level2DimensionCodes": [],
                                  "businessScope": ""
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Disabled("Requires SmartBI SDK runtime dependencies supplied by the company environment")
    void buildsYearOnYearSmartBiMetricAndPeriodFilters() throws Exception {
        mockMvc.perform(post("/api/attribution/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "metricCode": "transactionCount",
                                  "currentPeriod": "2026-07",
                                  "comparisonType": "yearOnYear",
                                  "level1DimensionCode": "acquiringRegion",
                                  "level2DimensionCodes": ["transactionMedia"],
                                  "businessScope": ""
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comparisonPeriod").value("2025年7月"))
                .andExpect(jsonPath("$.smartBiQueries[0].request.columns[0]").value("acpt_trans_cnt_m"))
                .andExpect(jsonPath("$.smartBiQueries[0].request.columns[1]").value("acpt_trans_cnt_tb"))
                .andExpect(jsonPath("$.smartBiQueries[0].request.filters[1].values[0]").value("2025-07"));
    }
}
