package com.company.paymentanalysis.controller;

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

@SpringBootTest(properties = {
    "llm.mock-enabled=true",
    "chat.memory.redis-enabled=false"
})
@AutoConfigureMockMvc
class ChatQueryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ChatConversationMemoryService memoryService;

    @Test
    void returnsTheQueryStateAfterTheLangGraphWorkflowBoundary() throws Exception {
        mockMvc.perform(post("/api/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId":"test-user",
                                  "sessionId":"workflow-boundary",
                                  "message":"沿用当前条件",
                                  "context":{
                                    "metricIds":["trans_cnt_m"],
                                    "dimensionIds":[],
                                    "dimensionFilters":[],
                                    "sorts":[]
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("confirming"))
                .andExpect(jsonPath("$.context.metricIds[0]").value("trans_cnt_m"))
                .andExpect(jsonPath("$.queryPlan.smartBiRequest.columns[0]").value("trans_cnt_m"))
                .andExpect(jsonPath("$.workflowSteps[4].node").value("generateChatResponse"));
    }

    @Test
    void doesNotExposeAnAttributionArtifactInsideAQueryConversation() throws Exception {
        memoryService.saveAttributionArtifact(
                "test-user", "attribution-follow-up", "人民币交易金额归因（2026-07 对比 2026-06）",
                "7 月人民币交易金额下降，收单机构 A 是主要负向驱动。",
                "指标=人民币交易金额；周期=2026-07 对比 2026-06；深度=2",
                "主路径=收单机构=收单机构A；关键发现=机构 A 贡献最大负向变化");

        mockMvc.perform(post("/api/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId":"test-user",
                                  "sessionId":"attribution-follow-up",
                                  "message":"把刚才的归因写成一段给业务负责人的说明",
                                  "context":{"metricIds":[],"dimensionIds":[],"dimensionFilters":[],"sorts":[]}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.derivedFromArtifactIds.length()").value(0))
                .andExpect(jsonPath("$.result").doesNotExist());
    }
}
