package com.company.paymentanalysis.smartbi;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.paymentanalysis.controller.ChatQueryController.DimensionFilter;
import com.company.paymentanalysis.controller.ChatQueryController.QueryContext;
import com.company.paymentanalysis.controller.ChatQueryController.SortSpec;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

class SmartBiQueryBuilderTest {

    @Test
    void buildsTheExactSmartBiQueryRequestContract() {
        SmartBiProperties properties =
                new SmartBiProperties("http://smartbi", "/query", "payment_dataset", "", "");
        SmartBiQueryBuilder builder = new SmartBiQueryBuilder(properties);
        QueryContext context = new QueryContext(
                List.of("transactionAmount", "transactionCount"),
                List.of("tradeMonth"),
                List.of(new DimensionFilter("tradeYear", "EQUALS", List.of("2025"))),
                List.of());

        QueryRequest request = builder.build(context);

        assertThat(request.dataSetId()).isEqualTo("payment_dataset");
        assertThat(request.rows()).containsExactly("sett_dt_Month2");
        assertThat(request.columns()).containsExactly("trans_amt", "trans_cnt");
        assertThat(request.filters()).singleElement().satisfies(filter -> {
            assertThat(filter.id()).isEqualTo("1");
            assertThat(filter.name()).isEqualTo("sett_dt_Year");
            assertThat(filter.operation()).isEqualTo("EQUALS");
            assertThat(filter.values()).containsExactly("2025");
        });
        assertThat(request.relationNode().relation()).isEqualTo("AND");
        assertThat(request.relationNode().childNodes()).hasSize(1);
        assertThat(request.relationNode().childNodes().get(0).filter())
                .isEqualTo(request.filters().get(0));
    }

    @Test
    void mapsDimensionFiltersAndOrderedSortsToPhysicalFields() {
        SmartBiQueryBuilder builder = new SmartBiQueryBuilder(
                new SmartBiProperties("http://smartbi", "/query", "payment_dataset", "", ""));
        QueryContext context = new QueryContext(
                List.of("transactionAmount"),
                List.of("region", "channel"),
                List.of(
                        new DimensionFilter("region", "IN", List.of("华东", "华南")),
                        new DimensionFilter("channel", "EQUALS", List.of("线上渠道"))),
                List.of(
                        new SortSpec("transactionAmount", "DESC"),
                        new SortSpec("region", "ASC")));

        QueryRequest request = builder.build(context);

        assertThat(request.filters()).hasSize(2);
        assertThat(request.filters().get(0).name()).isEqualTo("region_name");
        assertThat(request.filters().get(0).operation()).isEqualTo("IN");
        assertThat(request.filters().get(0).values()).containsExactly("华东", "华南");
        assertThat(request.filters().get(1).name()).isEqualTo("accept_channel");
        assertThat(request.relationNode().relation()).isEqualTo("AND");
        assertThat(request.relationNode().childNodes()).hasSize(2);
        assertThat(request.sorts()).containsExactly(
                new SmartBiModels.Sort("trans_amt", "DESC"),
                new SmartBiModels.Sort("region_name", "ASC"));
    }

    @Test
    void mapsContinuousDateRangeAsAnOrdinaryDimensionFilter() {
        SmartBiQueryBuilder builder = new SmartBiQueryBuilder(
                new SmartBiProperties("http://smartbi", "/query", "payment_dataset", "", ""));
        QueryContext context = new QueryContext(
                List.of("transactionCount"), List.of("tradeDate"),
                List.of(new DimensionFilter(
                        "tradeDate", "BETWEEN", List.of("2026-02-01", "2026-07-31"))),
                List.of(new SortSpec("tradeDate", "ASC")));

        QueryRequest request = builder.build(context);

        assertThat(request.rows()).containsExactly("sett_dt_Day");
        assertThat(request.filters()).singleElement().satisfies(filter -> {
            assertThat(filter.name()).isEqualTo("trade_date");
            assertThat(filter.operation()).isEqualTo("BETWEEN");
            assertThat(filter.values()).containsExactly("2026-02-01", "2026-07-31");
        });
    }
}
