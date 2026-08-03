package com.company.paymentanalysis.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
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
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient.ChatMessage;
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
        when(interpreter.engineLabel(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void passesTheSelectedModelIntoTheQueryWorkflow() throws Exception {
        when(interpreter.interpret(any(), any())).thenReturn(result(new QueryAction(
                "QUERY", plan("SET", List.of("transactionAmount")),
                plan("CLEAR", List.of()), clearFilters(), new SortAction("CLEAR", List.of()))));

        mockMvc.perform(post("/api/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId":"test-user",
                                  "sessionId":"selected-model",
                                  "message":"查询交易金额",
                                  "model":"glm-4.7-flashx",
                                  "context":null
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionEngine")
                        .value(org.hamcrest.Matchers.containsString("glm-4.7-flashx")));

        verify(interpreter).interpret(
                argThat(request -> "glm-4.7-flashx".equals(request.model())), any());
    }

    @Test
    void rejectsModelsOutsideTheConfiguredAllowList() throws Exception {
        mockMvc.perform(post("/api/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId":"test-user",
                                  "sessionId":"invalid-model",
                                  "message":"查询交易金额",
                                  "model":"unknown-model",
                                  "context":null
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(interpreter, never()).interpret(any(), any());
    }

    @Test
    void treatsYearAsAFilterAndQueriesOnlyAfterConfirmation() throws Exception {
        when(interpreter.interpret(any(), any())).thenReturn(result(new QueryAction(
                "QUERY",
                plan("SET", List.of("transactionAmount")),
                plan("SET", List.of("tradeMonth")),
                new FilterAction(List.of(
                        new FilterOperation("SET", "tradeYear", "EQUALS", List.of("2025")))),
                new SortAction("CLEAR", List.of()))));

        mockMvc.perform(post("/api/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId":"test-user",
                                  "sessionId":"year-month",
                                  "message":"我要看2025年的每个月交易金额",
                                  "context":null
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("confirming"))
                .andExpect(jsonPath("$.context.dimensionIds[0]").value("tradeMonth"))
                .andExpect(jsonPath("$.context.dimensionFilters[0].dimensionId").value("tradeYear"))
                .andExpect(jsonPath("$.queryPlan.smartBiRequest.rows[0]").value("sett_dt_Month2"))
                .andExpect(jsonPath("$.queryPlan.smartBiRequest.filters[0].name").value("sett_dt_Year"))
                .andExpect(jsonPath("$.queryPlan.smartBiRequest.filters[0].values[0]").value("2025"))
                .andExpect(jsonPath("$.reply")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("时间范围"))))
                .andExpect(jsonPath("$.result").doesNotExist());
        verify(smartBiClient, never()).query(any());

        mockMvc.perform(post("/api/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId":"test-user",
                                  "sessionId":"year-month",
                                  "message":"确认执行",
                                  "confirmed":true,
                                  "context":{
                                    "metricIds":["transactionAmount"],
                                    "dimensionIds":["tradeMonth"],
                                    "dimensionFilters":[{
                                      "dimensionId":"tradeYear",
                                      "operator":"EQUALS",
                                      "values":["2025"]
                                    }],
                                    "sorts":[]
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.queryExplanation").isNotEmpty())
                .andExpect(jsonPath("$.queryAction.filterAction.operations[0].dimensionId")
                        .value("tradeYear"))
                .andExpect(jsonPath("$.result.rows.length()").value(12));
        verify(smartBiClient, times(1)).query(any());
    }

    @Test
    void buildsContinuousDateRangeFromDimensionFilter() throws Exception {
        when(interpreter.interpret(any(), any())).thenReturn(result(new QueryAction(
                "QUERY",
                plan("SET", List.of("transactionCount")),
                plan("SET", List.of("tradeDate")),
                new FilterAction(List.of(new FilterOperation(
                        "SET", "tradeDate", "BETWEEN", List.of("2026-02-01", "2026-07-31")))),
                new SortAction("SET", List.of(new SortItem("tradeDate", "ASC"))))));

        mockMvc.perform(post("/api/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId":"test-user",
                                  "sessionId":"date-range",
                                  "message":"查近半年每天交易笔数，按日升序",
                                  "context":null
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("confirming"))
                .andExpect(jsonPath("$.queryPlan.smartBiRequest.rows[0]").value("sett_dt_Day"))
                .andExpect(jsonPath("$.queryPlan.smartBiRequest.filters[0].name").value("trade_date"))
                .andExpect(jsonPath("$.queryPlan.smartBiRequest.filters[0].operation").value("BETWEEN"))
                .andExpect(jsonPath("$.queryPlan.smartBiRequest.filters[0].values.length()").value(2))
                .andExpect(jsonPath("$.queryPlan.sqlPreview")
                        .value(org.hamcrest.Matchers.containsString(
                                "trade_date BETWEEN '2026-02-01' AND '2026-07-31'")));
    }

    @Test
    void validatesOnlyMetricAndSortConsistency() throws Exception {
        when(interpreter.interpret(any(), any())).thenReturn(result(new QueryAction(
                "QUERY",
                plan("SET", List.of("transactionCount")),
                plan("CLEAR", List.of()),
                clearFilters(),
                new SortAction("SET", List.of(new SortItem("region", "DESC"))))));

        mockMvc.perform(post("/api/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"test-user","sessionId":"bad-sort","message":"测试","context":null}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("clarifying"))
                .andExpect(jsonPath("$.reply")
                        .value(org.hamcrest.Matchers.containsString("排序字段对应的度量或分组维度")))
                .andExpect(jsonPath("$.reply")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("时间范围"))));
    }

    @Test
    void appliesCompleteModelStateWithoutLeakingPreviousConditions() throws Exception {
        when(interpreter.interpret(any(), any())).thenReturn(new QueryActionResult(
                new QueryAction(
                        "QUERY",
                        plan("SET", List.of("transactionCount")),
                        plan("SET", List.of("channel")),
                        new FilterAction(List.of(
                                new FilterOperation("SET", "channel", "EQUALS", List.of("online")))),
                        new SortAction("CLEAR", List.of())),
                "改为交易笔数，按受理渠道分组并筛选线上渠道，同时清除旧排序。",
                new LlmResultMessage("glm-4-flash-250414", "assistant", "{}", List.of())));

        mockMvc.perform(post("/api/chat/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId":"test-user",
                                  "sessionId":"replace-complete-state",
                                  "message":"改为按线上受理渠道查询交易笔数，不要排序",
                                  "context":{
                                    "metricIds":["transactionAmount"],
                                    "dimensionIds":["region"],
                                    "dimensionFilters":[{
                                      "dimensionId":"region",
                                      "operator":"EQUALS",
                                      "values":["Europe"]
                                    }],
                                    "sorts":[{"fieldId":"transactionAmount","direction":"DESC"}]
                                  }
                                }
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("confirming"))
                .andExpect(jsonPath("$.context.metricIds.length()").value(1))
                .andExpect(jsonPath("$.context.metricIds[0]").value("transactionCount"))
                .andExpect(jsonPath("$.context.dimensionIds.length()").value(1))
                .andExpect(jsonPath("$.context.dimensionIds[0]").value("channel"))
                .andExpect(jsonPath("$.context.dimensionFilters.length()").value(1))
                .andExpect(jsonPath("$.context.dimensionFilters[0].dimensionId").value("channel"))
                .andExpect(jsonPath("$.context.dimensionFilters[0].values[0]").value("online"))
                .andExpect(jsonPath("$.context.sorts.length()").value(0))
                .andExpect(jsonPath("$.queryPlan.smartBiRequest.columns[0]").value("trans_cnt"))
                .andExpect(jsonPath("$.queryPlan.smartBiRequest.rows[0]").value("accept_channel"))
                .andExpect(jsonPath("$.queryPlan.smartBiRequest.filters[0].name").value("accept_channel"))
                .andExpect(jsonPath("$.queryPlan.smartBiRequest.sorts.length()").value(0));
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
                        new ChatMessage("system", "schema"), new ChatMessage("user", "query"))));
    }

    private ActionPlan plan(String action, List<String> ids) {
        return new ActionPlan(List.of(new ActionOperation(action, ids)));
    }

    private FilterAction clearFilters() {
        return new FilterAction(List.of(new FilterOperation("CLEAR", "", "", List.of())));
    }
}
