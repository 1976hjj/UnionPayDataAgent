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

@SpringBootTest(properties = {"chat.memory.redis-enabled=false", "llm.mock-enabled=true"})
@AutoConfigureMockMvc
class ChatQueryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void completesAQueryAcrossMultipleTurns() throws Exception {
        mockMvc.perform(post("/api/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": "test-user-multi",
                                  "sessionId": "demo-multi",
                                  "message": "查7月交易金额",
                                  "context": {
                                    "startDate": "",
                                    "endDate": "",
                                    "periodLabel": "",
                                    "metricIds": [],
                                    "dimensionIds": []
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.context.periodLabel").value("2026年7月"))
                .andExpect(jsonPath("$.context.metricIds[0]").value("transactionAmount"))
                .andExpect(jsonPath("$.result.columns.length()").value(2))
                .andExpect(jsonPath("$.result.rows.length()").value(1))
                .andExpect(jsonPath("$.executionEngine")
                        .value("LangGraph4j → Mock LLM → Mock SmartBI"))
                .andExpect(jsonPath("$.workflowSteps.length()").value(6))
                .andExpect(jsonPath("$.workflowSteps[0].node").value("interpretMessage"))
                .andExpect(jsonPath("$.workflowSteps[4].node").value("executeMockSmartBiQuery"))
                .andExpect(jsonPath("$.llmMessage.role").value("assistant"))
                .andExpect(jsonPath("$.llmMessage.content").isNotEmpty())
                .andExpect(jsonPath("$.llmMessage.requestMessages.length()").value(2))
                .andExpect(jsonPath("$.llmMessage.requestMessages[0].role").value("system"))
                .andExpect(jsonPath("$.llmMessage.requestMessages[1].role").value("user"))
                .andExpect(jsonPath("$.queryPlan.columns[0]").value("交易金额 (trans_amt)"))
                .andExpect(jsonPath("$.queryPlan.filters[0].operation").value("BETWEEN"))
                .andExpect(jsonPath("$.queryPlan.sqlPreview")
                        .value(org.hamcrest.Matchers.containsString("SUM(trans_amt)")))
                .andExpect(jsonPath("$.queryPlan.sqlPreview")
                        .value(org.hamcrest.Matchers.containsString("trade_date BETWEEN")));

        mockMvc.perform(post("/api/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": "test-user-multi",
                                  "sessionId": "demo-multi",
                                  "message": "按受理渠道",
                                  "context": {
                                    "startDate": "2026-07-01",
                                    "endDate": "2026-07-30",
                                    "periodLabel": "2026年7月",
                                    "metricIds": ["transactionAmount"],
                                    "dimensionIds": []
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.result.columns.length()").value(2))
                .andExpect(jsonPath("$.result.rows.length()").value(4))
                .andExpect(jsonPath("$.context.metricIds[0]").value("transactionAmount"))
                .andExpect(jsonPath("$.context.dimensionIds[0]").value("channel"))
                .andExpect(jsonPath("$.queryPlan.rows[0]").value("受理渠道 (accept_channel)"));
    }

    @Test
    void removesOnlyTheRequestedDimensionAndKeepsOtherContext() throws Exception {
        mockMvc.perform(post("/api/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": "test-user-remove-dimension",
                                  "sessionId": "demo-remove-dimension",
                                  "message": "地区维度取消",
                                  "context": {
                                    "startDate": "2026-07-01",
                                    "endDate": "2026-07-30",
                                    "periodLabel": "本月（默认）",
                                    "metricIds": ["transactionAmount"],
                                    "dimensionIds": ["region", "channel"]
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.context.dimensionIds.length()").value(1))
                .andExpect(jsonPath("$.context.dimensionIds[0]").value("channel"))
                .andExpect(jsonPath("$.queryPlan.rows.length()").value(1))
                .andExpect(jsonPath("$.queryPlan.rows[0]").value("受理渠道 (accept_channel)"));
    }

    @Test
    void removesAndAddsDimensionsInTheSameTurn() throws Exception {
        mockMvc.perform(post("/api/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": "test-user-edit-dimensions",
                                  "sessionId": "demo-edit-dimensions",
                                  "message": "取消地区维度，增加受理渠道",
                                  "context": {
                                    "startDate": "2026-07-01",
                                    "endDate": "2026-07-30",
                                    "periodLabel": "本月（默认）",
                                    "metricIds": ["transactionAmount"],
                                    "dimensionIds": ["region", "merchantType"]
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.context.dimensionIds.length()").value(2))
                .andExpect(jsonPath("$.context.dimensionIds[0]").value("merchantType"))
                .andExpect(jsonPath("$.context.dimensionIds[1]").value("channel"));
    }

    @Test
    void substitutesOneDimensionWithoutDroppingUnrelatedDimensions() throws Exception {
        mockMvc.perform(post("/api/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": "test-user-substitute-dimension",
                                  "sessionId": "demo-substitute-dimension",
                                  "message": "用受理渠道取代地区维度",
                                  "context": {
                                    "startDate": "2026-07-01",
                                    "endDate": "2026-07-30",
                                    "periodLabel": "本月（默认）",
                                    "metricIds": ["transactionAmount"],
                                    "dimensionIds": ["region", "merchantType"]
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.context.dimensionIds.length()").value(2))
                .andExpect(jsonPath("$.context.dimensionIds[0]").value("merchantType"))
                .andExpect(jsonPath("$.context.dimensionIds[1]").value("channel"));
    }

    @Test
    void removesAndAddsMetricsInTheSameTurn() throws Exception {
        mockMvc.perform(post("/api/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": "test-user-edit-metrics",
                                  "sessionId": "demo-edit-metrics",
                                  "message": "取消交易金额，增加交易笔数",
                                  "context": {
                                    "startDate": "2026-07-01",
                                    "endDate": "2026-07-30",
                                    "periodLabel": "本月（默认）",
                                    "metricIds": ["transactionAmount", "successRate"],
                                    "dimensionIds": ["merchantType"]
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.context.metricIds.length()").value(2))
                .andExpect(jsonPath("$.context.metricIds[0]").value("successRate"))
                .andExpect(jsonPath("$.context.metricIds[1]").value("transactionCount"));
    }

    @Test
    void clearsOnlyDimensionsWithoutResettingOtherConditions() throws Exception {
        mockMvc.perform(post("/api/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": "test-user-clear-dimensions",
                                  "sessionId": "demo-clear-dimensions",
                                  "message": "清空全部维度",
                                  "context": {
                                    "startDate": "2026-07-01",
                                    "endDate": "2026-07-30",
                                    "periodLabel": "本月（默认）",
                                    "metricIds": ["transactionAmount"],
                                    "dimensionIds": ["region", "merchantType"]
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.context.metricIds[0]").value("transactionAmount"))
                .andExpect(jsonPath("$.context.dimensionIds.length()").value(0));
    }

    @Test
    void defaultsToCurrentMonthWhenTimeIsOmitted() throws Exception {
        mockMvc.perform(post("/api/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": "test-user-default",
                                  "sessionId": "demo-default",
                                  "message": "查交易笔数",
                                  "context": null
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.context.periodLabel").value("本月（默认）"))
                .andExpect(jsonPath("$.context.dimensionIds.length()").value(0))
                .andExpect(jsonPath("$.result.rows.length()").value(1))
                .andExpect(jsonPath("$.workflowSteps[5].status").value("COMPLETED"));
    }

    @Test
    void groupsRecentMonthsByTheModelMonthDimension() throws Exception {
        mockMvc.perform(post("/api/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": "test-user-month-dimension",
                                  "sessionId": "demo-month-dimension",
                                  "message": "交易笔数，近三个月每个月看",
                                  "context": {
                                    "startDate": "2026-05-01",
                                    "endDate": "2026-07-30",
                                    "periodLabel": "近三个月",
                                    "metricIds": [],
                                    "dimensionIds": []
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.context.dimensionIds[0]").value("tradeMonth"))
                .andExpect(jsonPath("$.queryPlan.rows[0]").value("月 (sett_dt_Month2)"))
                .andExpect(jsonPath("$.queryPlan.sqlPreview")
                        .value(org.hamcrest.Matchers.containsString("GROUP BY sett_dt_Month2")));
    }

    @Test
    void rejectsNonQueryConversation() throws Exception {
        mockMvc.perform(post("/api/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": "test-user-rejected",
                                  "sessionId": "demo-rejected",
                                  "message": "帮我写一首诗",
                                  "context": null
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("rejected"))
                .andExpect(jsonPath("$.result").doesNotExist())
                .andExpect(jsonPath("$.queryPlan").doesNotExist())
                .andExpect(jsonPath("$.workflowSteps.length()").value(6))
                .andExpect(jsonPath("$.workflowSteps[3].status").value("SKIPPED"))
                .andExpect(jsonPath("$.workflowSteps[4].status").value("SKIPPED"));
    }

    @Test
    void keepsQueryAvailableButDoesNotPretendToPersistWhenRedisIsDisabled() throws Exception {
        mockMvc.perform(post("/api/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": "history-user",
                                  "sessionId": "history-conversation",
                                  "message": "查最近7天支付成功率",
                                  "context": null
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value("history-conversation"));

        mockMvc.perform(get("/api/chat/conversations")
                        .param("userId", "history-user"))
                .andExpect(status().isServiceUnavailable());

        mockMvc.perform(get("/api/chat/memory/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.backend").value("Redis"))
                .andExpect(jsonPath("$.redisConfigured").value(false))
                .andExpect(jsonPath("$.available").value(false));

        mockMvc.perform(get("/api/system/dependencies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallStatus").value("DEGRADED"))
                .andExpect(jsonPath("$.dependencies[0].code").value("redis"))
                .andExpect(jsonPath("$.dependencies[0].status").value("DOWN"))
                .andExpect(jsonPath("$.dependencies[1].status").value("MOCK"))
                .andExpect(jsonPath("$.dependencies[2].status").value("MOCK"));
    }
}
