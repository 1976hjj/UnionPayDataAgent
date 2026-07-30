package com.company.paymentanalysis.calculation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.paymentanalysis.normalize.NormalizedDataRow;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DefaultAnalysisCalculationEngineRankingTest {

    private final AnalysisCalculationEngine engine = new DefaultAnalysisCalculationEngine();

    @Test
    void ranksTopNAndBottomNWithoutTrustingInputOrder() {
        List<NormalizedDataRow> rows = List.of(
                row(0, "华东", "10"),
                row(1, "华南", "30"),
                row(2, "华北", "20"));

        RankingResult top = engine.rank(
                rows,
                new RankingRequest("amount", SortDirection.DESC, 2, NullValuePolicy.EXCLUDE));
        RankingResult bottom = engine.rank(
                rows,
                new RankingRequest("amount", SortDirection.ASC, 2, NullValuePolicy.EXCLUDE));

        assertThat(top.items()).extracting(item -> item.dimensionValues().get("region"))
                .containsExactly("华南", "华北");
        assertThat(bottom.items()).extracting(item -> item.dimensionValues().get("region"))
                .containsExactly("华东", "华北");
        assertThat(top.items()).extracting(RankingItem::rank).containsExactly(1, 2);
    }

    @Test
    void usesOriginalIndexAsDeterministicTieBreaker() {
        List<NormalizedDataRow> rows = List.of(
                row(8, "B", "10"),
                row(3, "A", "10"));

        RankingResult result = engine.rank(
                rows,
                new RankingRequest("amount", SortDirection.DESC, 2, NullValuePolicy.EXCLUDE));

        assertThat(result.items()).extracting(item -> item.dimensionValues().get("region"))
                .containsExactly("A", "B");
    }

    @Test
    void appliesNullPoliciesAndWarnsWhenResultIsShort() {
        NormalizedDataRow nullRow =
                new NormalizedDataRow(Map.of("region", "空值"), nullableMetric(), 0);
        NormalizedDataRow validRow = row(1, "有效", "5");

        RankingResult excluded = engine.rank(
                List.of(nullRow, validRow),
                new RankingRequest("amount", SortDirection.DESC, 3, NullValuePolicy.EXCLUDE));
        RankingResult zeroed = engine.rank(
                List.of(nullRow, validRow),
                new RankingRequest("amount", SortDirection.ASC, 2, NullValuePolicy.TREAT_AS_ZERO));

        assertThat(excluded.items()).hasSize(1);
        assertThat(excluded.warnings()).hasSize(2);
        assertThat(zeroed.items().get(0).metricValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThatThrownBy(() -> engine.rank(
                        List.of(nullRow),
                        new RankingRequest(
                                "amount", SortDirection.DESC, 1, NullValuePolicy.ERROR)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
    }

    @Test
    void validatesLimitAndKeepsLargeAmountPrecision() {
        assertThatThrownBy(() ->
                        new RankingRequest("amount", SortDirection.DESC, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        new RankingRequest("amount", SortDirection.DESC, 101, null))
                .isInstanceOf(IllegalArgumentException.class);

        RankingResult result = engine.rank(
                List.of(
                        row(0, "A", "999999999999999999999.98"),
                        row(1, "B", "999999999999999999999.99")),
                new RankingRequest("amount", SortDirection.DESC, 5, null));
        assertThat(result.items().get(0).dimensionValues()).containsEntry("region", "B");
    }

    private NormalizedDataRow row(int index, String region, String amount) {
        return new NormalizedDataRow(
                Map.of("region", region),
                Map.of("amount", new BigDecimal(amount)),
                index);
    }

    private Map<String, BigDecimal> nullableMetric() {
        Map<String, BigDecimal> metrics = new java.util.LinkedHashMap<>();
        metrics.put("amount", null);
        return metrics;
    }
}
