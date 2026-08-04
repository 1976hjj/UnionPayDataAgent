package com.company.paymentanalysis.smartbi;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.paymentanalysis.controller.ChatQueryController.DimensionFilter;
import com.company.paymentanalysis.controller.ChatQueryController.QueryContext;
import com.company.paymentanalysis.controller.ChatQueryController.SortSpec;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class SmartBiQueryBuilderTest {

    private final SmartBiProperties properties =
            new SmartBiProperties("http://smartbi", "/query", "merchant_daily", "", "");
    private final SmartBiQueryBuilder builder = new SmartBiQueryBuilder(properties);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void buildsOfficialJsonForMetricsDimensionsFiltersAndOrderBy() {
        QueryRequest request = builder.build(new QueryContext(
                List.of("transactionAmount", "transactionCount"),
                List.of("tradeMonth", "region"),
                List.of(new DimensionFilter("region", "IN", List.of("east", "south"))),
                List.of(new SortSpec("transactionAmount", "DESC"), new SortSpec("region", "ASC"))));

        JsonNode json = objectMapper.valueToTree(request);

        assertThat(json.path("dataSetId").asText()).isEqualTo("merchant_daily");
        assertThat(json.path("rows")).hasSize(2);
        assertThat(json.path("columns")).hasSize(2);
        assertThat(json.path("filters").get(0).path("name").asText()).isEqualTo("region_name");
        assertThat(json.path("relationNode").path("leaf").isBoolean()).isTrue();
        assertThat(json.path("orderBys").get(0).path("fieldName").asText()).isEqualTo("trans_amt");
        assertThat(json.path("orderBys").get(0).path("type").asText()).isEqualTo("DESC");
        assertThat(json.path("orderBys").get(0).path("orderPriority").asInt()).isEqualTo(1);
        assertThat(json.has("sorts")).isFalse();
    }

    @Test
    void convertsAContinuousRangeToDocumentedComparisonFilters() {
        QueryRequest request = builder.build(new QueryContext(
                List.of("transactionCount"),
                List.of("tradeDate"),
                List.of(new DimensionFilter(
                        "tradeDate", "BETWEEN", List.of("2026-02-01", "2026-07-31"))),
                List.of()));

        assertThat(request.filters())
                .extracting(filter -> filter.operation())
                .containsExactly("GREATER_EQUALS", "LESS_EQUALS");
        assertThat(request.filters())
                .extracting(filter -> filter.values())
                .containsExactly(List.of("2026-02-01"), List.of("2026-07-31"));
        assertThat(request.relationNode().childNodes()).hasSize(2);
    }
}
