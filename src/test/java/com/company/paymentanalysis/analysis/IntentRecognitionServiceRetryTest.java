package com.company.paymentanalysis.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient.LlmResultMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class IntentRecognitionServiceRetryTest {

    @Test
    void retriesRealLlmWhenStrictIntentSchemaIsInvalid() {
        OpenAiCompatibleLlmClient llm = mock(OpenAiCompatibleLlmClient.class);
        when(llm.completeWithMessage(anyList(), anyString()))
                .thenReturn(message("""
                        {"intent":"A_LESS_THAN_B"}
                        """))
                .thenReturn(message("""
                        {
                          "intent":"COMPARE_QUERY",
                          "confidence":1.0,
                          "metricText":"交易金额",
                          "dimensionTexts":[],
                          "filters":[],
                          "comparisonSubjects":[
                            {"label":"6月","filters":[{"field":"tradeDate","operator":"BETWEEN",
                              "values":["2026-06-01","2026-06-30"]}]},
                            {"label":"7月","filters":[{"field":"tradeDate","operator":"BETWEEN",
                              "values":["2026-07-01","2026-07-31"]}]}
                          ],
                          "requestedCalculations":["A_LESS_THAN_B","ABSOLUTE_DIFFERENCE"],
                          "topN":null,
                          "needsDataQuery":true,
                          "needsKnowledgeBase":false,
                          "missingSlots":[],
                          "clarificationQuestion":""
                        }
                        """));
        IntentRecognitionService service =
                new IntentRecognitionService(
                        llm,
                        new ObjectMapper(),
                        new TimeRangeResolver());

        IntentRecognitionResult result = service.recognize(
                        "6月比7月少了多少交易金额",
                        new AnalysisContext(
                                LocalDate.of(2026, 7, 31),
                                "",
                                List.of(),
                                List.of()))
                .result();

        assertThat(result.intent()).isEqualTo(IntentType.COMPARE_QUERY);
        assertThat(result.requestedCalculations())
                .containsExactly("A_LESS_THAN_B", "ABSOLUTE_DIFFERENCE");
        verify(llm, times(2)).completeWithMessage(anyList(), anyString());
    }

    private LlmResultMessage message(String content) {
        return new LlmResultMessage("glm", "assistant", content, List.of());
    }
}
