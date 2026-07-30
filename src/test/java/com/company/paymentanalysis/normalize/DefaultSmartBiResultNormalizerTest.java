package com.company.paymentanalysis.normalize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.paymentanalysis.analysis.IntentType;
import com.company.paymentanalysis.analysis.QueryPlan;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryResponse;
import com.company.paymentanalysis.smartbi.SmartBiProperties;
import com.company.paymentanalysis.smartbi.SmartBiQueryBuilder;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DefaultSmartBiResultNormalizerTest {

    private final DefaultSmartBiResultNormalizer normalizer =
            new DefaultSmartBiResultNormalizer(new SmartBiQueryBuilder(properties()));

    @Test
    void normalizesDimensionsAndMetricWithoutLosingPrecision() {
        QueryResponse response = new QueryResponse(
                "request-1",
                List.of(Map.of(
                        "acq_mkt_ch", "英国",
                        "acpt_trans_rmb_amt_m", "9,999,999,999,999.1234")),
                Map.of());

        List<NormalizedDataRow> rows = normalizer.normalize(response, plan());

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.dimensions()).containsEntry("acquiringRegion", "英国");
            assertThat(row.metrics())
                    .containsEntry(
                            "rmbAmount",
                            new BigDecimal("9999999999999.1234"));
            assertThat(row.originalIndex()).isZero();
        });
    }

    @Test
    void preservesNullMetricForCalculationPolicyToHandle() {
        QueryResponse response = new QueryResponse(
                "request-2",
                List.of(new java.util.LinkedHashMap<>(Map.of("acq_mkt_ch", "法国"))),
                Map.of());
        response.data().get(0).put("acpt_trans_rmb_amt_m", null);

        List<NormalizedDataRow> rows = normalizer.normalize(response, plan());

        assertThat(rows.get(0).metrics()).containsEntry("rmbAmount", null);
    }

    @Test
    void rejectsMissingOrInvalidFieldsInsteadOfGuessingAnotherColumn() {
        QueryResponse missing = new QueryResponse(
                "request-3",
                List.of(Map.of("acq_mkt_ch", "英国", "other_metric", 10)),
                Map.of());
        QueryResponse invalid = new QueryResponse(
                "request-4",
                List.of(Map.of(
                        "acq_mkt_ch", "英国",
                        "acpt_trans_rmb_amt_m", "not-a-number")),
                Map.of());

        assertThatThrownBy(() -> normalizer.normalize(missing, plan()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("acpt_trans_rmb_amt_m");
        assertThatThrownBy(() -> normalizer.normalize(invalid, plan()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不是合法数字");
    }

    private QueryPlan plan() {
        return new QueryPlan(
                IntentType.GROUP_QUERY,
                1.0,
                "rmbAmount",
                List.of("acquiringRegion"),
                List.of(),
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
                "/query",
                "payment_query_dataset",
                "",
                "");
    }
}
