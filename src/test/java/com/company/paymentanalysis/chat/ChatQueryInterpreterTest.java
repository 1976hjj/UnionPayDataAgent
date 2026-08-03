package com.company.paymentanalysis.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.paymentanalysis.chat.ChatQueryInterpreter.ActionOperation;
import com.company.paymentanalysis.chat.ChatQueryInterpreter.FilterOperation;
import com.company.paymentanalysis.chat.ChatQueryInterpreter.QueryAction;
import com.company.paymentanalysis.chat.ChatQueryInterpreter.QueryInterpretationException;
import com.company.paymentanalysis.controller.ChatQueryController.ChatRequest;
import com.company.paymentanalysis.controller.ChatQueryController.QueryContext;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient.LlmResultMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ChatQueryInterpreterTest {

    @Test
    void sendsTheSelectedModelToEveryLlmAttempt() {
        OpenAiCompatibleLlmClient llm = mock(OpenAiCompatibleLlmClient.class);
        String response = protocolJson("{\"action\":\"CLEAR\",\"items\":[]}");
        when(llm.completeWithMessage(anyList(), anyString(), eq("glm-4.7")))
                .thenReturn(new LlmResultMessage("glm-4.7", "assistant", response, List.of()));
        ChatQueryInterpreter interpreter = new ChatQueryInterpreter(
                llm, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-07-31T00:00:00Z"), ZoneOffset.UTC));

        var result = interpreter.interpret(new ChatRequest(
                "user", "conversation", "查询交易金额", QueryContext.empty(), "glm-4.7", false),
                QueryContext.empty());

        assertThat(result.llmMessage().model()).isEqualTo("glm-4.7");
        verify(llm).completeWithMessage(anyList(), anyString(), eq("glm-4.7"));
    }

    @Test
    void keepsTemporalRulesGenericAndInjectsFieldIdsOnlyAsMetadata() {
        String prompt = interpreter("{}").systemPrompt();
        int metadataStart = prompt.indexOf("动态元数据（");
        String rules = prompt.substring(0, metadataStart);

        assertThat(rules)
                .contains("explanation", "简短中文说明", "单个时间点", "EQUALS", "当前日期只是换算相对时间的基准", "昨天为 D-1 天", "N-1 天", "不得生成默认排序")
                .doesNotContain("tradeYear", "tradeMonth", "tradeDate");
        assertThat(prompt.substring(metadataStart))
                .contains("transactionAmount", "tradeYear", "tradeMonth", "tradeDate");
    }

    @Test
    void acceptsSingleRelativeDateAsOneEqualsValue() {
        var result = interpreter("""
                {
                  "intent":"QUERY",
                  "metricAction":{"operations":[{"action":"SET","ids":["transactionAmount"]}]},
                  "dimensionAction":{"operations":[{"action":"SET","ids":["region"]}]},
                  "filterAction":{"operations":[
                    {"action":"SET","dimensionId":"tradeDate","operator":"EQUALS","values":["2026-07-30"]}
                  ]},
                  "explanation":"查询昨天各地区的交易金额，并按金额倒序。",
                  "sortAction":{"action":"SET","items":[{"fieldId":"transactionAmount","direction":"DESC"}]}
                }
                """).interpret(new ChatRequest(
                        "user", "conversation", "查询昨天各地区交易金额", QueryContext.empty()),
                QueryContext.empty());
        QueryAction action = result.action();

        assertThat(action.filterAction().operations()).containsExactly(
                new FilterOperation("SET", "tradeDate", "EQUALS", List.of("2026-07-30")));
        assertThat(result.explanation()).contains("昨天", "地区", "倒序");
    }

    @Test
    void parsesYearAsAnOrdinaryDimensionFilter() {
        QueryAction action = interpreter("""
                {
                  "intent":"QUERY",
                  "metricAction":{"operations":[{"action":"SET","ids":["transactionAmount"]}]},
                  "dimensionAction":{"operations":[{"action":"SET","ids":["tradeMonth"]}]},
                  "filterAction":{"operations":[
                    {"action":"SET","dimensionId":"tradeYear","operator":"EQUALS","values":["2025"]}
                  ]},
                  "explanation":"查询2025年按月汇总的交易金额。",
                  "sortAction":{"action":"CLEAR","items":[]}
                }
                """).interpret(new ChatRequest(
                        "user", "conversation", "我要看2025年的每个月交易金额", QueryContext.empty()),
                        QueryContext.empty()).action();

        assertThat(action.metricAction().operations())
                .containsExactly(new ActionOperation("SET", List.of("transactionAmount")));
        assertThat(action.dimensionAction().operations().get(0).ids()).containsExactly("tradeMonth");
        assertThat(action.filterAction().operations()).containsExactly(
                new FilterOperation("SET", "tradeYear", "EQUALS", List.of("2025")));
    }

    @Test
    void parsesContinuousDateRangeWithoutEnumeratingDates() {
        QueryAction action = interpreter("""
                {
                  "intent":"QUERY",
                  "metricAction":{"operations":[{"action":"SET","ids":["transactionCount"]}]},
                  "dimensionAction":{"operations":[{"action":"SET","ids":["tradeDate"]}]},
                  "filterAction":{"operations":[
                    {"action":"SET","dimensionId":"tradeDate","operator":"BETWEEN","values":["2026-02-01","2026-07-31"]}
                  ]},
                  "explanation":"查询近半年按日汇总的交易笔数。",
                  "sortAction":{"action":"SET","items":[{"fieldId":"tradeDate","direction":"ASC"}]}
                }
                """).interpret(new ChatRequest(
                        "user", "conversation", "查近半年每天的交易笔数", QueryContext.empty()),
                        QueryContext.empty()).action();

        assertThat(action.filterAction().operations().get(0).operator()).isEqualTo("BETWEEN");
        assertThat(action.filterAction().operations().get(0).values())
                .containsExactly("2026-02-01", "2026-07-31");
    }

    @Test
    void rejectsRemovedStandalonePeriodFields() {
        ChatQueryInterpreter interpreter = interpreter("""
                {
                  "intent":"QUERY",
                  "periodAction":"SET",
                  "startDate":"2025-01-01",
                  "endDate":"2025-12-31",
                  "periodLabel":"2025年",
                  "metricAction":{"operations":[{"action":"SET","ids":["transactionAmount"]}]},
                  "dimensionAction":{"operations":[{"action":"SET","ids":["tradeMonth"]}]},
                  "filterAction":{"operations":[{"action":"CLEAR","dimensionId":"","operator":"","values":[]}]},
                  "explanation":"查询指定年份的数据。",
                  "sortAction":{"action":"CLEAR","items":[]}
                }
                """);

        assertThatThrownBy(() -> interpreter.interpret(
                new ChatRequest("user", "conversation", "查2025年", QueryContext.empty()),
                QueryContext.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("periodAction");
    }

    @Test
    void rejectsUnknownIdsAndProviderEnvelopes() {
        ChatQueryInterpreter unknown = interpreter("""
                {
                  "intent":"QUERY",
                  "metricAction":{"operations":[{"action":"SET","ids":["profit"]}]},
                  "dimensionAction":{"operations":[{"action":"CLEAR","ids":[]}]},
                  "filterAction":{"operations":[{"action":"CLEAR","dimensionId":"","operator":"","values":[]}]},
                  "explanation":"查询利润。",
                  "sortAction":{"action":"CLEAR","items":[]}
                }
                """);
        assertThatThrownBy(() -> unknown.interpret(
                new ChatRequest("user", "conversation", "查利润", QueryContext.empty()), QueryContext.empty()))
                .hasMessageContaining("不支持的 id");

        ChatQueryInterpreter wrapped = interpreter("""
                {"QueryAction":{
                  "intent":"QUERY",
                  "metricAction":{"operations":[{"action":"CLEAR","ids":[]}]},
                  "dimensionAction":{"operations":[{"action":"CLEAR","ids":[]}]},
                  "filterAction":{"operations":[{"action":"CLEAR","dimensionId":"","operator":"","values":[]}]},
                  "explanation":"沿用当前状态。",
                  "sortAction":{"action":"CLEAR","items":[]}
                }}
                """);
        assertThatThrownBy(() -> wrapped.interpret(
                new ChatRequest("user", "conversation", "继续", QueryContext.empty()), QueryContext.empty()))
                .hasMessageContaining("缺少字段");
    }

    @Test
    void retriesOnceWhenBetweenDoesNotContainTwoBoundaries() {
        OpenAiCompatibleLlmClient llm = mock(OpenAiCompatibleLlmClient.class);
        String invalid = """
                {
                  "intent":"QUERY",
                  "metricAction":{"operations":[{"action":"SET","ids":["transactionCount"]}]},
                  "dimensionAction":{"operations":[{"action":"SET","ids":["tradeDate"]}]},
                  "filterAction":{"operations":[{"action":"SET","dimensionId":"tradeDate","operator":"BETWEEN","values":["2026-02-01"]}]},
                  "explanation":"查询近半年每天的交易笔数。",
                  "sortAction":{"action":"CLEAR","items":[]}
                }
                """;
        String repaired = """
                {
                  "intent":"QUERY",
                  "metricAction":{"operations":[{"action":"SET","ids":["transactionCount"]}]},
                  "dimensionAction":{"operations":[{"action":"SET","ids":["tradeDate"]}]},
                  "filterAction":{"operations":[{"action":"SET","dimensionId":"tradeDate","operator":"BETWEEN","values":["2026-02-01","2026-07-31"]}]},
                  "explanation":"查询近半年每天的交易笔数。",
                  "sortAction":{"action":"CLEAR","items":[]}
                }
                """;
        when(llm.completeWithMessage(anyList(), anyString())).thenReturn(
                new LlmResultMessage("glm-4-flash-250414", "assistant", invalid, List.of()),
                new LlmResultMessage("glm-4-flash-250414", "assistant", repaired, List.of()));
        ChatQueryInterpreter interpreter = new ChatQueryInterpreter(
                llm, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-07-31T00:00:00Z"), ZoneOffset.UTC));

        QueryAction action = interpreter.interpret(new ChatRequest(
                "user", "conversation", "查近半年每天交易笔数", QueryContext.empty()),
                QueryContext.empty()).action();

        assertThat(action.filterAction().operations().get(0).values())
                .containsExactly("2026-02-01", "2026-07-31");
        verify(llm, times(2)).completeWithMessage(anyList(), anyString());
    }

    @Test
    void exposesBothLlmAttemptsWhenProtocolRepairStillFails() {
        OpenAiCompatibleLlmClient llm = mock(OpenAiCompatibleLlmClient.class);
        String invalid = """
                {
                  "intent":"QUERY",
                  "metricAction":{"operations":[{"action":"SET","ids":["transactionAmount"]}]},
                  "dimensionAction":{"operations":[{"action":"SET","ids":[]}]},
                  "filterAction":{"operations":[{"action":"CLEAR","dimensionId":"","operator":"","values":[]}]},
                  "sortAction":{"action":"CLEAR","items":[]},
                  "explanation":"查询最近三个月的交易金额。"
                }
                """;
        when(llm.completeWithMessage(anyList(), anyString())).thenAnswer(invocation ->
                new LlmResultMessage(
                        "GLM-4.7", "assistant", invalid,
                        List.copyOf(invocation.getArgument(0)),
                        "{\"choices\":[{\"message\":{\"content\":\"invalid\"}}]}"));
        ChatQueryInterpreter interpreter = new ChatQueryInterpreter(
                llm, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-03T00:00:00Z"), ZoneOffset.UTC));

        QueryInterpretationException exception = catchThrowableOfType(
                () -> interpreter.interpret(new ChatRequest(
                        "user", "conversation", "查下澳门的最近3个月交易金额", QueryContext.empty()),
                        QueryContext.empty()),
                QueryInterpretationException.class);

        assertThat(exception.stage()).isEqualTo("PROTOCOL_VALIDATION");
        assertThat(exception).hasMessageContaining("dimensionAction 的 SET 必须包含完整 ids");
        assertThat(exception.llmMessage().requestMessages()).hasSize(4);
        assertThat(exception.llmMessage().requestMessages().get(2).role()).isEqualTo("assistant");
        assertThat(exception.llmMessage().requestMessages().get(2).content()).isEqualTo(invalid);
        assertThat(exception.llmMessage().requestMessages().get(3).content())
                .contains("未通过协议校验", "dimensionAction 的 SET 必须包含完整 ids");
        assertThat(exception.llmMessage().rawResponse()).contains("choices");
        verify(llm, times(2)).completeWithMessage(anyList(), anyString());
    }

    @Test
    void acceptsOperationsWrapperForSortActionWithoutAnotherLlmCall() {
        OpenAiCompatibleLlmClient llm = mock(OpenAiCompatibleLlmClient.class);
        String wrappedSort = """
                {
                  "intent":"QUERY",
                  "metricAction":{"operations":[{"action":"SET","ids":["transactionAmount"]}]},
                  "dimensionAction":{"operations":[{"action":"SET","ids":["region"]}]},
                  "filterAction":{"operations":[{"action":"SET","dimensionId":"region","operator":"IN","values":["Europe"]}]},
                  "explanation":"查询欧洲地区并按地区倒序。",
                  "sortAction":{"operations":[{"action":"SET","items":[{"fieldId":"region","direction":"DESC"}]}]}
                }
                """;
        when(llm.completeWithMessage(anyList(), anyString())).thenReturn(
                new LlmResultMessage("glm-4-flash-250414", "assistant", wrappedSort, List.of()));
        ChatQueryInterpreter interpreter = new ChatQueryInterpreter(
                llm, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-07-31T00:00:00Z"), ZoneOffset.UTC));

        var result = interpreter.interpret(new ChatRequest(
                "user", "conversation", "地区按降序", QueryContext.empty()), QueryContext.empty());

        assertThat(result.action().sortAction().action()).isEqualTo("SET");
        assertThat(result.action().sortAction().items())
                .containsExactly(new ChatQueryInterpreter.SortItem("region", "DESC"));
        verify(llm).completeWithMessage(anyList(), anyString());
    }

    @Test
    void clearsExistingSortWhenModelUsesListStyleClearShape() {
        OpenAiCompatibleLlmClient llm = mock(OpenAiCompatibleLlmClient.class);
        String clearSort = """
                {
                  "intent":"QUERY",
                  "metricAction":{"operations":[{"action":"SET","ids":["transactionAmount"]}]},
                  "dimensionAction":{"operations":[{"action":"SET","ids":["region"]}]},
                  "filterAction":{"operations":[{"action":"CLEAR","dimensionId":"","operator":"","values":[]}]},
                  "sortAction":{"operations":[{"action":"CLEAR","ids":[]}]},
                  "explanation":"保留交易金额和地区分组，并清除原有排序。"
                }
                """;
        when(llm.completeWithMessage(anyList(), anyString())).thenReturn(
                new LlmResultMessage("glm-4-flash-250414", "assistant", clearSort, List.of()));
        ChatQueryInterpreter interpreter = new ChatQueryInterpreter(
                llm, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-07-31T00:00:00Z"), ZoneOffset.UTC));
        QueryContext current = new QueryContext(
                List.of("transactionAmount"), List.of("region"), List.of(),
                List.of(new com.company.paymentanalysis.controller.ChatQueryController.SortSpec(
                        "transactionAmount", "DESC")));

        var result = interpreter.interpret(new ChatRequest(
                "user", "conversation", "不要排序", current), current);

        assertThat(result.action().sortAction().action()).isEqualTo("CLEAR");
        assertThat(result.action().sortAction().items()).isEmpty();
        verify(llm).completeWithMessage(anyList(), anyString());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validSortShapes")
    void parsesEverySupportedSortProtocolShape(
            String name, String sortJson, String expectedAction,
            String expectedField, String expectedDirection) {
        OpenAiCompatibleLlmClient llm = mock(OpenAiCompatibleLlmClient.class);
        when(llm.completeWithMessage(anyList(), anyString())).thenReturn(new LlmResultMessage(
                "glm-4-flash-250414", "assistant", protocolJson(sortJson), List.of()));
        ChatQueryInterpreter interpreter = new ChatQueryInterpreter(
                llm, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-07-31T00:00:00Z"), ZoneOffset.UTC));

        var result = interpreter.interpret(new ChatRequest(
                "user", "conversation", "调整排序", QueryContext.empty()), QueryContext.empty());

        assertThat(result.action().sortAction().action()).isEqualTo(expectedAction);
        if ("CLEAR".equals(expectedAction)) {
            assertThat(result.action().sortAction().items()).isEmpty();
        } else {
            assertThat(result.action().sortAction().items()).containsExactly(
                    new ChatQueryInterpreter.SortItem(expectedField, expectedDirection));
        }
        assertThat(result.explanation()).isEqualTo("协议解析测试。");
        verify(llm).completeWithMessage(anyList(), anyString());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validFilterShapes")
    void parsesEveryFilterOperatorExactly(
            String name, String filterJson, String operator, List<String> values) {
        OpenAiCompatibleLlmClient llm = mock(OpenAiCompatibleLlmClient.class);
        String response = protocolJson("{\"action\":\"CLEAR\",\"items\":[]}", filterJson);
        when(llm.completeWithMessage(anyList(), anyString())).thenReturn(new LlmResultMessage(
                "glm-4-flash-250414", "assistant", response, List.of()));
        ChatQueryInterpreter interpreter = new ChatQueryInterpreter(
                llm, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-07-31T00:00:00Z"), ZoneOffset.UTC));

        var result = interpreter.interpret(new ChatRequest(
                "user", "conversation", "调整过滤", QueryContext.empty()), QueryContext.empty());

        assertThat(result.action().filterAction().operations()).containsExactly(
                new FilterOperation("SET", "region", operator, values));
        verify(llm).completeWithMessage(anyList(), anyString());
    }

    @Test
    void preservesCompleteMultiValueProtocolStateExactly() {
        String response = """
                {
                  "intent":"QUERY",
                  "metricAction":{"operations":[{"action":"SET","ids":["transactionAmount","transactionCount"]}]},
                  "dimensionAction":{"operations":[{"action":"SET","ids":["tradeDate","region"]}]},
                  "filterAction":{"operations":[
                    {"action":"SET","dimensionId":"tradeDate","operator":"BETWEEN","values":["2026-07-01","2026-07-31"]},
                    {"action":"SET","dimensionId":"region","operator":"IN","values":["华东","华南"]}
                  ]},
                  "sortAction":{"action":"SET","items":[
                    {"fieldId":"transactionAmount","direction":"DESC"},
                    {"fieldId":"region","direction":"ASC"}
                  ]},
                  "explanation":"查询7月华东和华南地区的金额与笔数，并按金额、地区排序。"
                }
                """;

        var result = interpreter(response).interpret(new ChatRequest(
                "user", "conversation", "完整协议", QueryContext.empty()), QueryContext.empty());

        assertThat(result.action().metricAction().operations()).containsExactly(
                new ActionOperation("SET", List.of("transactionAmount", "transactionCount")));
        assertThat(result.action().dimensionAction().operations()).containsExactly(
                new ActionOperation("SET", List.of("tradeDate", "region")));
        assertThat(result.action().filterAction().operations()).containsExactly(
                new FilterOperation("SET", "tradeDate", "BETWEEN", List.of("2026-07-01", "2026-07-31")),
                new FilterOperation("SET", "region", "IN", List.of("华东", "华南")));
        assertThat(result.action().sortAction().items()).containsExactly(
                new ChatQueryInterpreter.SortItem("transactionAmount", "DESC"),
                new ChatQueryInterpreter.SortItem("region", "ASC"));
        assertThat(result.explanation()).isEqualTo("查询7月华东和华南地区的金额与笔数，并按金额、地区排序。");
    }

    @Test
    void parsesCompleteClearProtocolStateExactly() {
        String response = """
                {
                  "intent":"QUERY",
                  "metricAction":{"operations":[{"action":"CLEAR","ids":[]}]},
                  "dimensionAction":{"operations":[{"action":"CLEAR","ids":[]}]},
                  "filterAction":{"operations":[{"action":"CLEAR","dimensionId":"","operator":"","values":[]}]},
                  "sortAction":{"action":"CLEAR","items":[]},
                  "explanation":"清空全部查询条件。"
                }
                """;

        var result = interpreter(response).interpret(new ChatRequest(
                "user", "conversation", "清空", QueryContext.empty()), QueryContext.empty());

        assertThat(result.action().metricAction().operations()).containsExactly(
                new ActionOperation("CLEAR", List.of()));
        assertThat(result.action().dimensionAction().operations()).containsExactly(
                new ActionOperation("CLEAR", List.of()));
        assertThat(result.action().filterAction().operations()).containsExactly(
                new FilterOperation("CLEAR", "", "", List.of()));
        assertThat(result.action().sortAction()).isEqualTo(
                new ChatQueryInterpreter.SortAction("CLEAR", List.of()));
    }

    private static Stream<Arguments> validSortShapes() {
        return Stream.of(
                Arguments.of("标准 CLEAR", "{\"action\":\"CLEAR\",\"items\":[]}", "CLEAR", "", ""),
                Arguments.of("标准 SET", "{\"action\":\"SET\",\"items\":[{\"fieldId\":\"transactionAmount\",\"direction\":\"DESC\"}]}", "SET", "transactionAmount", "DESC"),
                Arguments.of("operations 包装 SET", "{\"operations\":[{\"action\":\"SET\",\"items\":[{\"fieldId\":\"region\",\"direction\":\"ASC\"}]}]}", "SET", "region", "ASC"),
                Arguments.of("operations 包装 CLEAR items", "{\"operations\":[{\"action\":\"CLEAR\",\"items\":[]}]}", "CLEAR", "", ""),
                Arguments.of("operations 包装 CLEAR ids", "{\"operations\":[{\"action\":\"CLEAR\",\"ids\":[]}]}", "CLEAR", "", ""));
    }

    private static Stream<Arguments> validFilterShapes() {
        return Stream.of(
                Arguments.of("EQUALS 单值", "{\"action\":\"SET\",\"dimensionId\":\"region\",\"operator\":\"EQUALS\",\"values\":[\"华东\"]}", "EQUALS", List.of("华东")),
                Arguments.of("IN 多值", "{\"action\":\"SET\",\"dimensionId\":\"region\",\"operator\":\"IN\",\"values\":[\"华东\",\"华南\"]}", "IN", List.of("华东", "华南")),
                Arguments.of("BETWEEN 双边界", "{\"action\":\"SET\",\"dimensionId\":\"region\",\"operator\":\"BETWEEN\",\"values\":[\"A\",\"Z\"]}", "BETWEEN", List.of("A", "Z")));
    }

    private static String protocolJson(String sortJson) {
        return protocolJson(
                sortJson,
                "{\"action\":\"CLEAR\",\"dimensionId\":\"\",\"operator\":\"\",\"values\":[]}");
    }

    private static String protocolJson(String sortJson, String filterJson) {
        return """
                {
                  "intent":"QUERY",
                  "metricAction":{"operations":[{"action":"SET","ids":["transactionAmount"]}]},
                  "dimensionAction":{"operations":[{"action":"SET","ids":["region"]}]},
                  "filterAction":{"operations":[%s]},
                  "sortAction":%s,
                  "explanation":"协议解析测试。"
                }
                """.formatted(filterJson, sortJson);
    }

    @Test
    void preservesExistingSortWhenModelStillOmitsSortShapeAfterRepair() {
        OpenAiCompatibleLlmClient llm = mock(OpenAiCompatibleLlmClient.class);
        String incomplete = """
                {
                  "intent":"QUERY",
                  "metricAction":{"operations":[{"action":"SET","ids":["transactionAmount"]}]},
                  "dimensionAction":{"operations":[{"action":"SET","ids":["tradeMonth"]}]},
                  "filterAction":{"operations":[{"action":"CLEAR","dimensionId":"","operator":"","values":[]}]},
                  "explanation":"沿用当前查询状态。",
                  "sortAction":{}
                }
                """;
        when(llm.completeWithMessage(anyList(), anyString())).thenReturn(
                new LlmResultMessage("glm-4-flash-250414", "assistant", incomplete, List.of()),
                new LlmResultMessage("glm-4-flash-250414", "assistant", incomplete, List.of()));
        ChatQueryInterpreter interpreter = new ChatQueryInterpreter(
                llm, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-07-31T00:00:00Z"), ZoneOffset.UTC));
        QueryContext current = new QueryContext(
                List.of("transactionAmount"), List.of("tradeMonth"), List.of(),
                List.of(new com.company.paymentanalysis.controller.ChatQueryController.SortSpec(
                        "tradeMonth", "DESC")));

        var result = interpreter.interpret(new ChatRequest(
                "user", "conversation", "继续查询", current), current);

        assertThat(result.action().sortAction().action()).isEqualTo("SET");
        assertThat(result.action().sortAction().items())
                .containsExactly(new ChatQueryInterpreter.SortItem("tradeMonth", "DESC"));
        assertThat(result.normalizationNotes()).isNotEmpty();
        verify(llm, times(2)).completeWithMessage(anyList(), anyString());
    }

    private ChatQueryInterpreter interpreter(String responseJson) {
        OpenAiCompatibleLlmClient llm = mock(OpenAiCompatibleLlmClient.class);
        when(llm.completeWithMessage(anyList(), anyString()))
                .thenReturn(new LlmResultMessage(
                        "glm-4-flash-250414", "assistant", responseJson, List.of()));
        return new ChatQueryInterpreter(
                llm, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-07-31T00:00:00Z"), ZoneOffset.UTC));
    }
}
