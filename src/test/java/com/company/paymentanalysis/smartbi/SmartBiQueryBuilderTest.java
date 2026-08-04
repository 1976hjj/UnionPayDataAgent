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
            new SmartBiProperties("http://smartbi", "/query", "production_dataset", "", "");
    private final SmartBiQueryBuilder builder = new SmartBiQueryBuilder(properties);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void convertsProductionQueryContextToSmartBiRequest() {
        QueryRequest request = builder.build(new QueryContext(
                List.of("trans_rmb_amt_m"),
                List.of("sett_dt_Month2"),
                List.of(new DimensionFilter("acq_mkt_ch", "IN", List.of("上海", "北京"))),
                List.of(new SortSpec("trans_rmb_amt_m", "DESC"))));

        JsonNode json = objectMapper.valueToTree(request);

        assertThat(json.path("dataSetId").asText()).isEqualTo("production_dataset");
        assertThat(json.path("rows")).extracting(JsonNode::asText).containsExactly("sett_dt_Month2");
        assertThat(json.path("columns")).extracting(JsonNode::asText).containsExactly("trans_rmb_amt_m");
        assertThat(json.path("filters").get(0).path("name").asText()).isEqualTo("acq_mkt_ch");
        assertThat(json.path("orderBys").get(0).path("fieldName").asText())
                .isEqualTo("trans_rmb_amt_m");
        assertThat(json.path("orderBys").get(0).path("type").asText()).isEqualTo("DESC");
    }
}
