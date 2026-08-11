package com.company.paymentanalysis.attribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.paymentanalysis.attribution.AttributionModels.DimensionFilter;
import com.company.paymentanalysis.attribution.AttributionModels.EffectiveRequest;
import com.company.paymentanalysis.smartbi.SmartBiClient;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryRequest;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryResponse;
import com.company.paymentanalysis.smartbi.SmartBiProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AttributionQueryServiceTest {

    @Test
    void queriesEachPeriodSeparatelyUsingTheSameFlatFilterShapeAsChatQueries() {
        SmartBiClient client = mock(SmartBiClient.class);
        when(client.query(any())).thenAnswer(invocation -> response(invocation.getArgument(0)));
        AttributionQueryService service = new AttributionQueryService(
                client, new SmartBiProperties(
                        "dataset", false, "http://localhost", "http://smartbi", "user", "password"));
        EffectiveRequest request = new EffectiveRequest(
                "trans_rmb_amt_m", "2026-07", "2026-06",
                List.of(new DimensionFilter("acq_mkt_ch", "EQUALS", List.of("欧洲市场"))),
                2, 8, 4, 2, null);

        List<AttributionQueryService.SmartBiCall> events = new ArrayList<>();
        var execution = service.queryOverall(request, events::add);

        ArgumentCaptor<QueryRequest> captor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(client, times(2)).query(captor.capture());
        QueryRequest current = captor.getAllValues().get(0);
        QueryRequest comparison = captor.getAllValues().get(1);

        assertThat(current.columns()).containsExactly("trans_rmb_amt_m");
        assertThat(comparison.columns()).containsExactly("trans_rmb_amt_m");
        assertThat(current.filters()).extracting(filter -> filter.id()).containsExactly("1", "2");
        assertThat(current.filters().get(0).values()).containsExactly("2026-07");
        assertThat(comparison.filters().get(0).values()).containsExactly("2026-06");
        assertThat(current.relationNode().relation()).isEqualTo("AND");
        assertThat(current.relationNode().childNodes()).allMatch(node -> node.leaf());
        assertThat(execution.traces()).hasSize(2);
        assertThat(execution.response().data()).hasSize(2);
        assertThat(events).extracting(AttributionQueryService.SmartBiCall::status)
                .containsExactly("RUNNING", "COMPLETED", "RUNNING", "COMPLETED");
        assertThat(events.get(0).request()).isEqualTo(current);
        assertThat(events.get(0).request().rowsPerPage()).isEqualTo(9_999_999);
        assertThat(events.get(1).response().data()).hasSize(1);
        assertThat(events.get(0).callId()).isEqualTo(events.get(1).callId());
        assertThat(events.get(2).callId()).isEqualTo(events.get(3).callId());
    }

    @Test
    void keepsTheRequestAndErrorWhenSmartBiFails() {
        SmartBiClient client = mock(SmartBiClient.class);
        when(client.query(any())).thenThrow(new IllegalStateException("SmartBI timeout"));
        AttributionQueryService service = new AttributionQueryService(
                client, new SmartBiProperties(
                        "dataset", false, "http://localhost", "http://smartbi", "user", "password"));
        EffectiveRequest request = new EffectiveRequest(
                "trans_rmb_amt_m", "2026-07", "2026-06", List.of(),
                2, 8, 4, 2, null);
        List<AttributionQueryService.SmartBiCall> events = new ArrayList<>();

        assertThatThrownBy(() -> service.queryOverall(request, events::add))
                .hasMessageContaining("SmartBI timeout");

        assertThat(events).extracting(AttributionQueryService.SmartBiCall::status)
                .containsExactly("RUNNING", "FAILED");
        assertThat(events.get(1).request()).isEqualTo(events.get(0).request());
        assertThat(events.get(1).response()).isNull();
        assertThat(events.get(1).error()).isEqualTo("SmartBI timeout");
    }

    private QueryResponse response(QueryRequest request) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("sett_dt_Month2", request.filters().get(0).values().get(0));
        row.put("trans_rmb_amt_m", new BigDecimal("100"));
        return new QueryResponse("request-" + request.filters().get(0).values().get(0), List.of(row), Map.of());
    }
}
