package com.company.paymentanalysis.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
}
