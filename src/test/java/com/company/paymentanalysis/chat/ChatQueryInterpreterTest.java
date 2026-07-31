package com.company.paymentanalysis.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.paymentanalysis.chat.ChatQueryInterpreter.ActionOperation;
import com.company.paymentanalysis.chat.ChatQueryInterpreter.QueryAction;
import com.company.paymentanalysis.controller.ChatQueryController.ChatRequest;
import com.company.paymentanalysis.controller.ChatQueryController.QueryContext;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient.LlmResultMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatQueryInterpreterTest {

    @Test
    void acceptsOnlyTheQueryActionContract() {
        ChatQueryInterpreter interpreter = interpreter("""
                {
                  "intent": "QUERY",
                  "periodAction": "SET",
                  "startDate": "2026-02-01",
                  "endDate": "2026-07-31",
                  "periodLabel": "近半年",
                  "metricAction": {"operations":[{"action":"ADD","ids":["transactionAmount"]}]},
                  "dimensionAction": {"operations":[{"action":"ADD","ids":["region","tradeMonth"]}]},
                  "filterAction": {"operations":[{"action":"KEEP","dimensionId":"","operator":"","values":[]}]},
                  "sortAction": {"action":"KEEP","items":[]}
                }
                """);

        QueryAction action = interpreter.interpret(
                new ChatRequest("user", "conversation", "查近半年交易金额，按地区和月份", QueryContext.empty()),
                QueryContext.empty()).action();

        assertThat(action.intent()).isEqualTo("QUERY");
        assertThat(action.startDate()).isEqualTo("2026-02-01");
        assertThat(action.endDate()).isEqualTo("2026-07-31");
        assertThat(action.metricAction().operations())
                .containsExactly(new ActionOperation("ADD", List.of("transactionAmount")));
        assertThat(action.dimensionAction().operations())
                .containsExactly(new ActionOperation("ADD", List.of("region", "tradeMonth")));
    }

    @Test
    void rejectsUnknownIdsInsteadOfSilentlyChangingTheModelOutput() {
        ChatQueryInterpreter interpreter = interpreter("""
                {
                  "intent": "QUERY",
                  "periodAction": "KEEP",
                  "startDate": "",
                  "endDate": "",
                  "periodLabel": "",
                  "metricAction": {"operations":[{"action":"ADD","ids":["profit"]}]},
                  "dimensionAction": {"operations":[{"action":"KEEP","ids":[]}]},
                  "filterAction": {"operations":[{"action":"KEEP","dimensionId":"","operator":"","values":[]}]},
                  "sortAction": {"action":"KEEP","items":[]}
                }
                """);

        assertThatThrownBy(() -> interpreter.interpret(
                new ChatRequest("user", "conversation", "查利润", QueryContext.empty()),
                QueryContext.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的 id");
    }

    @Test
    void rejectsAnyIntentOtherThanQuery() {
        ChatQueryInterpreter interpreter = interpreter("""
                {
                  "intent": "TREND",
                  "periodAction": "KEEP",
                  "startDate": "",
                  "endDate": "",
                  "periodLabel": "",
                  "metricAction": {"operations":[{"action":"KEEP","ids":[]}]},
                  "dimensionAction": {"operations":[{"action":"KEEP","ids":[]}]},
                  "filterAction": {"operations":[{"action":"KEEP","dimensionId":"","operator":"","values":[]}]},
                  "sortAction": {"action":"KEEP","items":[]}
                }
                """);

        assertThatThrownBy(() -> interpreter.interpret(
                new ChatRequest("user", "conversation", "分析趋势", QueryContext.empty()),
                QueryContext.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只能是 QUERY");
    }

    @Test
    void unwrapsTheKnownProviderEnvelopeBeforeStrictValidation() {
        ChatQueryInterpreter interpreter = interpreter("""
                {
                  "QueryAction": {
                    "intent": "QUERY",
                    "periodAction": "KEEP",
                    "startDate": "",
                    "endDate": "",
                    "periodLabel": "",
                    "metricAction": {"operations":[{"action":"KEEP","ids":[]}]},
                    "dimensionAction": {"operations":[{"action":"ADD","ids":["region"]}]},
                    "filterAction": {"operations":[{"action":"KEEP","dimensionId":"","operator":"","values":[]}]},
                    "sortAction": {"action":"KEEP","items":[]}
                  }
                }
                """);

        QueryAction action = interpreter.interpret(
                new ChatRequest("user", "conversation", "加地区", QueryContext.empty()),
                QueryContext.empty()).action();

        assertThat(action.dimensionAction().operations())
                .containsExactly(new ActionOperation("ADD", List.of("region")));
    }

    @Test
    void parsesGroupingDimensionFilterAndMultiFieldSortFromModelJson() {
        ChatQueryInterpreter interpreter = interpreter("""
                {
                  "intent":"QUERY",
                  "periodAction":"KEEP",
                  "startDate":"",
                  "endDate":"",
                  "periodLabel":"",
                  "metricAction":{"operations":[{"action":"ADD","ids":["transactionAmount"]}]},
                  "dimensionAction":{"operations":[{"action":"ADD","ids":["region","channel"]}]},
                  "filterAction":{"operations":[
                    {"action":"SET","dimensionId":"region","operator":"IN","values":["华东","华南"]}
                  ]},
                  "sortAction":{"action":"SET","items":[
                    {"fieldId":"transactionAmount","direction":"DESC"},
                    {"fieldId":"region","direction":"ASC"}
                  ]}
                }
                """);

        QueryAction action = interpreter.interpret(
                new ChatRequest("user", "conversation", "model-controlled query", QueryContext.empty()),
                QueryContext.empty()).action();

        assertThat(action.dimensionAction().operations().get(0).ids())
                .containsExactly("region", "channel");
        assertThat(action.filterAction().operations().get(0).dimensionId()).isEqualTo("region");
        assertThat(action.filterAction().operations().get(0).values()).containsExactly("华东", "华南");
        assertThat(action.sortAction().items())
                .containsExactly(
                        new ChatQueryInterpreter.SortItem("transactionAmount", "DESC"),
                        new ChatQueryInterpreter.SortItem("region", "ASC"));
    }

    private ChatQueryInterpreter interpreter(String responseJson) {
        OpenAiCompatibleLlmClient llm = mock(OpenAiCompatibleLlmClient.class);
        when(llm.completeWithMessage(anyList(), anyString()))
                .thenReturn(new LlmResultMessage("glm-4-flash-250414", "assistant", responseJson, List.of()));
        return new ChatQueryInterpreter(
                llm, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-07-31T00:00:00Z"), ZoneOffset.UTC));
    }
}
