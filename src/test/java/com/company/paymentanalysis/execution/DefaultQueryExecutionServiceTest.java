package com.company.paymentanalysis.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.paymentanalysis.analysis.FilterCondition;
import com.company.paymentanalysis.analysis.IntentType;
import com.company.paymentanalysis.analysis.QueryPlan;
import com.company.paymentanalysis.normalize.DefaultSmartBiResultNormalizer;
import com.company.paymentanalysis.normalize.NormalizedDataRow;
import com.company.paymentanalysis.smartbi.SmartBiClient;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryRequest;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryResponse;
import com.company.paymentanalysis.smartbi.SmartBiProperties;
import com.company.paymentanalysis.smartbi.SmartBiQueryBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DefaultQueryExecutionServiceTest {

    private final SmartBiClient client = mock(SmartBiClient.class);
    private final SmartBiQueryBuilder queryBuilder =
            new SmartBiQueryBuilder(properties());
    private final QueryExecutionService service = new DefaultQueryExecutionService(
            queryBuilder,
            client,
            new DefaultSmartBiResultNormalizer(queryBuilder),
            new ObjectMapper());

    @Test
    void buildsRealRequestCallsClientAndKeepsRawAndNormalizedResults() {
        QueryResponse response = new QueryResponse(
                "smartbi-request-1",
                List.of(Map.of("acpt_trans_rmb_amt_m", new BigDecimal("126840000.25"))),
                Map.of("source", "SmartBI"));
        when(client.query(any(QueryRequest.class))).thenReturn(response);

        AnalysisExecutionResult result = service.execute(plan(IntentType.SINGLE_QUERY));

        ArgumentCaptor<QueryRequest> requestCaptor =
                ArgumentCaptor.forClass(QueryRequest.class);
        verify(client).query(requestCaptor.capture());
        QueryRequest sentRequest = requestCaptor.getValue();
        assertThat(sentRequest.dataSetId()).isEqualTo("payment_query_dataset");
        assertThat(sentRequest.rows()).isEmpty();
        assertThat(sentRequest.columns()).containsExactly("acpt_trans_rmb_amt_m");
        assertThat(sentRequest.filters()).singleElement().satisfies(filter -> {
            assertThat(filter.name()).isEqualTo("trade_date");
            assertThat(filter.operation()).isEqualTo("BETWEEN");
            assertThat(filter.values()).containsExactly("2026-06-01", "2026-06-30");
        });

        assertThat(result.status()).isEqualTo(ExecutionStatus.SUCCESS);
        assertThat(result.rawData()).isSameAs(response);
        assertThat(result.queryRecords()).singleElement().satisfies(record -> {
            assertThat(record.queryId()).isNotBlank();
            assertThat(record.smartBiRequestJson())
                    .contains("\"dataSetId\":\"payment_query_dataset\"")
                    .contains("\"acpt_trans_rmb_amt_m\"");
            assertThat(record.rawResponse()).isSameAs(response);
            assertThat(record.status()).isEqualTo(QueryExecutionStatus.SUCCESS);
            assertThat(record.errorMessage()).isBlank();
        });
        assertThat((List<?>) result.calculationResult())
                .singleElement()
                .isInstanceOfSatisfying(NormalizedDataRow.class, row ->
                        assertThat(row.metrics().get("rmbAmount"))
                                .isEqualByComparingTo("126840000.25"));
    }

    @Test
    void buildsAndNormalizesGroupedQueryWithRequestedDimension() {
        QueryResponse response = new QueryResponse(
                "smartbi-request-group",
                List.of(Map.of(
                        "acq_mkt_ch", "英国",
                        "acpt_trans_rmb_amt_m", "8000000")),
                Map.of("source", "SmartBI"));
        when(client.query(any(QueryRequest.class))).thenReturn(response);

        AnalysisExecutionResult result = service.execute(plan(IntentType.GROUP_QUERY));

        ArgumentCaptor<QueryRequest> requestCaptor =
                ArgumentCaptor.forClass(QueryRequest.class);
        verify(client).query(requestCaptor.capture());
        assertThat(requestCaptor.getValue().rows()).containsExactly("acq_mkt_ch");
        assertThat((List<?>) result.calculationResult())
                .singleElement()
                .isInstanceOfSatisfying(NormalizedDataRow.class, row -> {
                    assertThat(row.dimensions())
                            .containsEntry("acquiringRegion", "英国");
                    assertThat(row.metrics().get("rmbAmount"))
                            .isEqualByComparingTo("8000000");
                });
    }

    @Test
    void distinguishesSuccessfulEmptyResultFromQueryFailure() {
        when(client.query(any(QueryRequest.class)))
                .thenReturn(new QueryResponse("smartbi-request-2", List.of(), Map.of()));

        AnalysisExecutionResult result = service.execute(plan(IntentType.SINGLE_QUERY));

        assertThat(result.status()).isEqualTo(ExecutionStatus.NO_DATA);
        assertThat(result.queryRecords().get(0).status())
                .isEqualTo(QueryExecutionStatus.SUCCESS);
        assertThat(result.warnings()).containsExactly("SmartBI 查询成功，但没有匹配数据");
    }

    @Test
    void recordsClientFailureWithoutInventingResponseOrResult() {
        when(client.query(any(QueryRequest.class)))
                .thenThrow(new IllegalStateException("SmartBI connection refused"));

        AnalysisExecutionResult result = service.execute(plan(IntentType.SINGLE_QUERY));

        assertThat(result.status()).isEqualTo(ExecutionStatus.QUERY_FAILED);
        assertThat(result.rawData()).isNull();
        assertThat(result.calculationResult()).isNull();
        assertThat(result.queryRecords()).singleElement().satisfies(record -> {
            assertThat(record.smartBiRequestJson()).contains("payment_query_dataset");
            assertThat(record.rawResponse()).isNull();
            assertThat(record.status()).isEqualTo(QueryExecutionStatus.FAILED);
            assertThat(record.errorMessage()).isEqualTo("SmartBI connection refused");
        });
    }

    private QueryPlan plan(IntentType intent) {
        return new QueryPlan(
                intent,
                0.99,
                "rmbAmount",
                intent == IntentType.GROUP_QUERY
                        ? List.of("acquiringRegion")
                        : List.of(),
                List.of(new FilterCondition(
                        "tradeDate",
                        "BETWEEN",
                        List.of("2026-06-01", "2026-06-30"))),
                List.of(),
                List.of(),
                null,
                true,
                false,
                List.of(),
                "");
    }

    private SmartBiProperties properties() {
        return new SmartBiProperties(
                "http://localhost",
                "/api/mock/smartbi/query",
                "payment_query_dataset",
                "",
                "");
    }
}
