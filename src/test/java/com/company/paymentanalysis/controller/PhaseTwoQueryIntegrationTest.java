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

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
            "chat.memory.redis-enabled=false",
            "llm.mock-enabled=true",
            "server.port=18082",
            "smartbi.base-url=http://localhost:18082"
        })
@AutoConfigureMockMvc
class PhaseTwoQueryIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void executesTrendThroughHttpSmartBiAndJavaCalculation() throws Exception {
        query("phase2-trend", "\u6700\u8fd16\u4e2a\u6708\u4ea4\u6613\u91d1\u989d\u8d70\u52bf")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.result.rows.length()").value(3))
                .andExpect(jsonPath("$.result.rows[0].period").value("2026-05"))
                .andExpect(jsonPath("$.workflowSteps[4].status").value("COMPLETED"))
                .andExpect(jsonPath("$.executionEngine")
                        .value(org.hamcrest.Matchers.containsString("Java Calculation Engine")));
    }

    @Test
    void executesTopNThroughHttpSmartBiAndJavaRanking() throws Exception {
        query(
                        "phase2-rank",
                        "6\u6708\u4ea4\u6613\u91d1\u989d\u6700\u9ad8\u7684\u524d3\u4e2a\u6536\u5355\u5730\u533a")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.rows.length()").value(3))
                .andExpect(jsonPath("$.result.rows[0].rank").value("1"))
                .andExpect(jsonPath("$.result.rows[0].transactionAmount").exists());
    }

    private org.springframework.test.web.servlet.ResultActions query(
            String sessionId,
            String message) throws Exception {
        return mockMvc.perform(post("/api/chat/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "userId": "phase2-user",
                          "sessionId": "%s",
                          "message": "%s",
                          "context": null
                        }
                        """.formatted(sessionId, message)));
    }
}
