package com.company.paymentanalysis.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.paymentanalysis.chat.ChatQueryInterpreter;
import com.company.paymentanalysis.chat.ChatQueryInterpreter.ActionOperation;
import com.company.paymentanalysis.chat.ChatQueryInterpreter.ActionPlan;
import com.company.paymentanalysis.chat.ChatQueryInterpreter.FilterAction;
import com.company.paymentanalysis.chat.ChatQueryInterpreter.FilterOperation;
import com.company.paymentanalysis.chat.ChatQueryInterpreter.QueryAction;
import com.company.paymentanalysis.chat.ChatQueryInterpreter.QueryActionResult;
import com.company.paymentanalysis.chat.ChatQueryInterpreter.SortAction;
import com.company.paymentanalysis.chat.ChatQueryInterpreter.SortItem;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient.LlmResultMessage;
import com.company.paymentanalysis.smartbi.SmartBiClient;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
            "chat.memory.redis-enabled=false",
            "server.port=18081",
            "smartbi.base-url=http://localhost:18081"
        })
@AutoConfigureMockMvc
class ChatQueryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatQueryInterpreter interpreter;

    @MockitoSpyBean
    private SmartBiClient smartBiClient;

    @BeforeEach
    void configureInterpreter() {
        when(interpreter.engineLabel()).thenReturn("Test LLM");
    }

    @Test
    void previewsAllParametersBeforeConfirmationAndQueriesOnlyAfterExplicitConfirmation() throws Exception {
        when(interpreter.interpret(any(), any())).thenReturn(result(new QueryAction(
                "QUERY", "SET", "2026-02-01", "2026-07-31", "近半年",
                operations(new ActionOperation("ADD", List.of("transactionAmount"))),
                operations(new ActionOperation("ADD", List.of("region", "tradeMonth"))),
                keepFilters(), keepSort())));

        mockMvc.perform(post("/api/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId":"test-user",
                                  "sessionId":"query-contract",
                                  "message":"查近半年交易金额，按地区和月份",
                                  "context":null
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("confirming"))
                .andExpect(jsonPath("$.queryAction.intent").value("QUERY"))
                .andExpect(jsonPath("$.queryAction.periodAction").value("SET"))
                .andExpect(jsonPath("$.queryAction.metricAction.operations[0].action").value("ADD"))
                .andExpect(jsonPath("$.queryPlan.smartBiRequest.dataSetId").isNotEmpty())
                .andExpect(jsonPath("$.queryPlan.smartBiRequest.rows[0]").value("region_name"))
                .andExpect(jsonPath("$.queryPlan.smartBiRequest.rows[1]").value("sett_dt_Month2"))
                .andExpect(jsonPath("$.queryPlan.smartBiRequest.columns[0]").value("trans_amt"))
                .andExpect(jsonPath("$.queryPlan.smartBiRequest.filters[0].name").value("trade_date"))
                .andExpect(jsonPath("$.queryPlan.smartBiRequest.filters[0].operation").value("BETWEEN"))
                .andExpect(jsonPath("$.queryPlan.smartBiRequest.filters[0].values[0]").value("2026-02-01"))
                .andExpect(jsonPath("$.workflowSteps[0].node").value("interpretQueryAction"))
                .andExpect(jsonPath("$.workflowSteps.length()").value(6))
                .andExpect(jsonPath("$.workflowSteps[4].status").value("SKIPPED"))
                .andExpect(jsonPath("$.workflowSteps[4].detail")
                        .value(org.hamcrest.Matchers.containsString("等待用户确认")))
                .andExpect(jsonPath("$.executionEngine").value("LangGraph4j → Test LLM → SmartBI Client"))
                .andExpect(jsonPath("$.llmMessage.requestMessages.length()").value(2))
                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.containsString("请确认本次查询参数")))
                .andExpect(jsonPath("$.result").doesNotExist());
        verify(smartBiClient, never()).query(any());

        mockMvc.perform(post("/api/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId":"test-user",
                                  "sessionId":"query-contract",
                                  "message":"确认执行",
                                  "confirmed":true,
                                  "context":{
                                    "startDate":"2026-02-01",
                                    "endDate":"2026-07-31",
                                    "periodLabel":"近半年",
                                    "metricIds":["transactionAmount"],
                                    "dimensionIds":["region","tradeMonth"]
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.queryAction.periodAction").value("KEEP"))
                .andExpect(jsonPath("$.llmMessage.requestMessages.length()").value(0))
                .andExpect(jsonPath("$.workflowSteps[4].status").value("COMPLETED"))
                .andExpect(jsonPath("$.result.rows").isNotEmpty());
        verify(smartBiClient, times(1)).query(any());
    }

    @Test
    void mergesQueryActionWithTheExistingConversationContext() throws Exception {
        when(interpreter.interpret(any(), any())).thenReturn(result(new QueryAction(
                "QUERY", "KEEP", "", "", "",
                operations(new ActionOperation("ADD", List.of("transactionCount"))),
                operations(new ActionOperation("REMOVE", List.of("region")),
                        new ActionOperation("ADD", List.of("channel"))),
                keepFilters(), keepSort())));

        mockMvc.perform(post("/api/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId":"test-user",
                                  "sessionId":"query-merge",
                                  "message":"再加交易笔数，并把地区换成受理渠道",
                                  "context":{
                                    "startDate":"2026-02-01",
                                    "endDate":"2026-07-31",
                                    "periodLabel":"近半年",
                                    "metricIds":["transactionAmount"],
                                    "dimensionIds":["region"]
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("confirming"))
                .andExpect(jsonPath("$.context.metricIds[0]").value("transactionAmount"))
                .andExpect(jsonPath("$.context.metricIds[1]").value("transactionCount"))
                .andExpect(jsonPath("$.context.dimensionIds[0]").value("channel"))
                .andExpect(jsonPath("$.queryPlan.smartBiRequest.columns.length()").value(2))
                .andExpect(jsonPath("$.queryPlan.smartBiRequest.rows[0]").value("accept_channel"))
                .andExpect(jsonPath("$.result").doesNotExist());
    }

    @Test
    void executesModelProvidedGroupFilterAndSortWithoutNaturalLanguageRules() throws Exception {
        QueryAction action = new QueryAction(
                "QUERY", "SET", "2026-07-01", "2026-07-31", "2026年7月",
                operations(new ActionOperation("ADD", List.of("transactionAmount"))),
                operations(new ActionOperation("ADD", List.of("region"))),
                new FilterAction(List.of(
                        new FilterOperation("SET", "region", "IN", List.of("华南", "华东")))),
                new SortAction("SET", List.of(new SortItem("transactionAmount", "ASC"))));
        when(interpreter.interpret(any(), any())).thenReturn(result(action));

        mockMvc.perform(post("/api/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId":"test-user",
                                  "sessionId":"group-filter-sort",
                                  "message":"the backend must use only the mocked QueryAction",
                                  "context":null
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("confirming"))
                .andExpect(jsonPath("$.queryAction.dimensionAction.operations[0].ids[0]").value("region"))
                .andExpect(jsonPath("$.queryAction.filterAction.operations[0].values[0]").value("华南"))
                .andExpect(jsonPath("$.queryAction.sortAction.items[0].direction").value("ASC"))
                .andExpect(jsonPath("$.queryPlan.smartBiRequest.rows[0]").value("region_name"))
                .andExpect(jsonPath("$.queryPlan.smartBiRequest.filters[1].name").value("region_name"))
                .andExpect(jsonPath("$.queryPlan.smartBiRequest.filters[1].operation").value("IN"))
                .andExpect(jsonPath("$.queryPlan.smartBiRequest.filters[1].values.length()").value(2))
                .andExpect(jsonPath("$.queryPlan.smartBiRequest.relationNode.childNodes.length()").value(2))
                .andExpect(jsonPath("$.queryPlan.smartBiRequest.sorts[0].field").value("trans_amt"))
                .andExpect(jsonPath("$.queryPlan.smartBiRequest.sorts[0].direction").value("ASC"))
                .andExpect(jsonPath("$.queryPlan.sqlPreview")
                        .value(org.hamcrest.Matchers.containsString("GROUP BY region_name")))
                .andExpect(jsonPath("$.queryPlan.sqlPreview")
                        .value(org.hamcrest.Matchers.containsString("ORDER BY trans_amt ASC")))
                .andExpect(jsonPath("$.result").doesNotExist());

        mockMvc.perform(post("/api/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId":"test-user",
                                  "sessionId":"group-filter-sort",
                                  "message":"确认执行",
                                  "confirmed":true,
                                  "context":{
                                    "startDate":"2026-07-01",
                                    "endDate":"2026-07-31",
                                    "periodLabel":"2026年7月",
                                    "metricIds":["transactionAmount"],
                                    "dimensionIds":["region"],
                                    "dimensionFilters":[{
                                      "dimensionId":"region",
                                      "operator":"IN",
                                      "values":["华南","华东"]
                                    }],
                                    "sorts":[{
                                      "fieldId":"transactionAmount",
                                      "direction":"ASC"
                                    }]
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.result.rows.length()").value(2))
                .andExpect(jsonPath("$.result.rows[0].region").value("华东"));
    }

    @Test
    void asksForMissingStateWithoutGeneratingSmartBiJson() throws Exception {
        when(interpreter.interpret(any(), any())).thenReturn(result(QueryAction.keep()));

        mockMvc.perform(post("/api/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId":"test-user",
                                  "sessionId":"query-incomplete",
                                  "message":"继续",
                                  "context":null
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("clarifying"))
                .andExpect(jsonPath("$.queryAction.intent").value("QUERY"))
                .andExpect(jsonPath("$.queryPlan").doesNotExist())
                .andExpect(jsonPath("$.workflowSteps[3].status").value("SKIPPED"))
                .andExpect(jsonPath("$.workflowSteps[4].status").value("SKIPPED"));
    }

    @Test
    void reportsRedisAsUnavailableWithoutInProcessFallback() throws Exception {
        mockMvc.perform(get("/api/chat/memory/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.backend").value("Redis"))
                .andExpect(jsonPath("$.redisConfigured").value(false))
                .andExpect(jsonPath("$.available").value(false));
    }

    private QueryActionResult result(QueryAction action) {
        return new QueryActionResult(action, new LlmResultMessage(
                "glm-4-flash-250414", "assistant", "{}", List.of(
                        new com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient.ChatMessage("system", "schema"),
                        new com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient.ChatMessage("user", "query"))));
    }

    private ActionPlan operations(ActionOperation... operations) {
        return new ActionPlan(List.of(operations));
    }

    private FilterAction keepFilters() {
        return new FilterAction(List.of(new FilterOperation("KEEP", "", "", List.of())));
    }

    private SortAction keepSort() {
        return new SortAction("KEEP", List.of());
    }
}
