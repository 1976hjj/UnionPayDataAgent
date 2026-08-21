package com.company.paymentanalysis.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.company.paymentanalysis.attribution.AttributionExecutionService;
import com.company.paymentanalysis.attribution.AttributionModels.AttributionReport;
import com.company.paymentanalysis.attribution.AttributionModels.AttributionResponse;
import com.company.paymentanalysis.attribution.AttributionTemplateModels.DimensionTemplate;
import com.company.paymentanalysis.attribution.AttributionTemplateModels.TemplateConversationState;
import com.company.paymentanalysis.chat.ChatConversationMemoryService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "llm.mock-enabled=true",
    "chat.memory.redis-enabled=false"
})
@AutoConfigureMockMvc
class AgentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ChatConversationMemoryService memoryService;

    @MockBean
    private AttributionExecutionService attributionExecutionService;

    @Test
    void delegatesTheBiEntryToTheExistingQueryWorkflow() throws Exception {
        mockMvc.perform(post("/api/agent/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "loginusername":"agent-user",
                                  "conversationId":"agent-query",
                                  "entryMode":"BI_CHAT",
                                  "message":"查询交易笔数",
                                  "queryContext":{
                                    "metricIds":["trans_cnt_m"],
                                    "dimensionIds":[],
                                    "dimensionFilters":[],
                                    "sorts":[]
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeSkill").value("QUERY"))
                .andExpect(jsonPath("$.viewModel.type").value("query"))
                .andExpect(jsonPath("$.viewModel.payload.context.metricIds[0]").value("trans_cnt_m"));
    }

    @Test
    void delegatesTheAttributionEntryToTheExistingTemplateConversation() throws Exception {
        mockMvc.perform(post("/api/agent/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId":"agent-user",
                                  "conversationId":"agent-attribution",
                                  "entryMode":"ATTRIBUTION",
                                  "message":"帮我看看最近交易情况为什么下跌"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeSkill").value("ATTRIBUTION"))
                .andExpect(jsonPath("$.viewModel.type").value("attribution-template"))
                .andExpect(jsonPath("$.viewModel.payload.template").exists());
    }

    @Test
    void confirmsOnlyTheAttributionTemplateStoredOnTheServer() throws Exception {
        DimensionTemplate draft = new DimensionTemplate(
                "人民币交易金额归因", "AUTO", "trans_rmb_amt_m", "人民币总金额",
                "2026-07", "2026-06", List.of(), List.of(), "AUTO", "DRAFT", "");
        memoryService.saveAttributionTurn("agent-user", "agent-confirm", "创建归因模板", "模板已生成",
                new TemplateConversationState("READY_TO_CONFIRM", draft, List.of(), List.of()));

        mockMvc.perform(post("/api/agent/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId":"agent-user",
                                  "conversationId":"agent-confirm",
                                  "entryMode":"ATTRIBUTION",
                                  "action":"CONFIRM",
                                  "message":"确认"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY_TO_EXECUTE"))
                .andExpect(jsonPath("$.activeSkill").value("ATTRIBUTION"))
                .andExpect(jsonPath("$.viewModel.payload.template.status").value("CONFIRMED"));
    }

    @Test
    void executesOnlyTheConfirmedAttributionTemplateStoredOnTheServer() throws Exception {
        DimensionTemplate confirmed = new DimensionTemplate(
                "人民币总金额归因", "AUTO", "trans_rmb_amt_m", "人民币总金额",
                "2026-07", "2026-06", List.of(), List.of(), "AUTO", "CONFIRMED", "");
        memoryService.saveAttributionTurn("agent-user", "agent-execute", "确认模板", "模板已确认",
                new TemplateConversationState("READY_TO_EXECUTE", confirmed, List.of(), List.of()));
        when(attributionExecutionService.execute(any())).thenReturn(
                new AttributionExecutionService.ExecutedAttribution(null, new AttributionResponse(
                        "completed", "trans_rmb_amt_m", "人民币总金额", "2026-07", "2026-06",
                        null, List.of(), List.of(), List.of(), List.of(), null,
                        new AttributionReport("归因完成", List.of(), List.of()),
                        0, "mock", List.of(), List.of())));

        mockMvc.perform(post("/api/agent/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId":"agent-user",
                                  "conversationId":"agent-execute",
                                  "entryMode":"ATTRIBUTION",
                                  "action":"EXECUTE",
                                  "attributionExecutionOptions":{"maxDepth":2,"maxQueries":8,"topN":5,"maxBranches":2},
                                  "message":"开始归因"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeSkill").value("ATTRIBUTION"))
                .andExpect(jsonPath("$.viewModel.type").value("attribution-result"))
                .andExpect(jsonPath("$.viewModel.payload.metricId").value("trans_rmb_amt_m"));
    }
}
