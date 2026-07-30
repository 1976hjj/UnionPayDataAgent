package com.company.paymentanalysis.calculation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.paymentanalysis.normalize.NormalizedDataRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DefaultAnalysisCalculationEngineTrendTest {

    private final AnalysisCalculationEngine engine = new DefaultAnalysisCalculationEngine();
    private final TrendRequest monthly =
            new TrendRequest("month", "amount", TimeGranularity.MONTH, SortDirection.ASC);

    @Test
    void sortsRealTimeAndSummarizesUpwardCrossYearTrend() {
        TrendSummary result = engine.summarizeTrend(
                List.of(
                        row(0, "2026-01", "30"),
                        row(1, "2025-11", "10"),
                        row(2, "2025-12", "20")),
                monthly);

        assertThat(result.points()).extracting(TrendPoint::periodLabel)
                .containsExactly("2025-11", "2025-12", "2026-01");
        assertThat(result.firstPoint().periodStart()).isEqualTo(LocalDate.of(2025, 11, 1));
        assertThat(result.lastPoint().value()).isEqualByComparingTo("30");
        assertThat(result.totalDifference()).isEqualByComparingTo("20");
        assertThat(result.totalChangeRate()).isEqualByComparingTo("2.0000");
        assertThat(result.direction()).isEqualTo(TrendDirection.UP);
        assertThat(result.maxPoint().periodLabel()).isEqualTo("2026-01");
        assertThat(result.minPoint().periodLabel()).isEqualTo("2025-11");
    }

    @Test
    void distinguishesDownFlatMixedAndSinglePoint() {
        assertThat(summary("30", "20", "10").direction()).isEqualTo(TrendDirection.DOWN);
        assertThat(summary("10", "10", "10").direction()).isEqualTo(TrendDirection.FLAT);
        assertThat(summary("10", "30", "20").direction()).isEqualTo(TrendDirection.MIXED);
        assertThat(engine.summarizeTrend(List.of(row(0, "2026-01", "10")), monthly)
                        .direction())
                .isEqualTo(TrendDirection.UNKNOWN);
    }

    @Test
    void warnsForMissingMonthWithoutFillingZero() {
        TrendSummary result = engine.summarizeTrend(
                List.of(row(0, "2026-01", "10"), row(1, "2026-03", "30")),
                monthly);

        assertThat(result.points()).hasSize(2);
        assertThat(result.warnings()).anyMatch(message -> message.contains("未自动补 0"));
    }

    @Test
    void skipsNullMetricButRejectsDuplicateOrInvalidPeriod() {
        TrendSummary result = engine.summarizeTrend(
                List.of(
                        row(0, "2026-01", "10"),
                        nullRow(1, "2026-02"),
                        row(2, "2026-03", "30")),
                monthly);

        assertThat(result.validPointCount()).isEqualTo(2);
        assertThat(result.warnings()).anyMatch(message -> message.contains("null"));
        assertThatThrownBy(() -> engine.summarizeTrend(
                        List.of(row(0, "2026-01", "10"), row(1, "2026-01", "20")),
                        monthly))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复时间点");
        assertThatThrownBy(() -> engine.summarizeTrend(
                        List.of(row(0, "2026/01", "10")),
                        monthly))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非法");
    }

    @Test
    void returnsNullRateAndWarningWhenFirstValueIsZero() {
        TrendSummary result = engine.summarizeTrend(
                List.of(row(0, "2026-01", "0"), row(1, "2026-02", "10")),
                monthly);

        assertThat(result.totalDifference()).isEqualByComparingTo("10");
        assertThat(result.totalChangeRate()).isNull();
        assertThat(result.warnings()).contains("首期值为0，无法计算累计变化率");
    }

    @Test
    void supportsChineseMonthLabelsAndDescendingPresentationOrder() {
        TrendSummary result = engine.summarizeTrend(
                List.of(row(0, "2026年6月", "10"), row(1, "2026年7月", "20")),
                new TrendRequest(
                        "month", "amount", TimeGranularity.MONTH, SortDirection.DESC));

        assertThat(result.points()).extracting(TrendPoint::periodLabel)
                .containsExactly("2026年7月", "2026年6月");
        assertThat(result.firstPoint().periodLabel()).isEqualTo("2026年6月");
        assertThat(result.lastPoint().periodLabel()).isEqualTo("2026年7月");
    }

    private TrendSummary summary(String first, String second, String third) {
        return engine.summarizeTrend(
                List.of(
                        row(0, "2026-01", first),
                        row(1, "2026-02", second),
                        row(2, "2026-03", third)),
                monthly);
    }

    private NormalizedDataRow row(int index, String month, String amount) {
        return new NormalizedDataRow(
                Map.of("month", month),
                Map.of("amount", new BigDecimal(amount)),
                index);
    }

    private NormalizedDataRow nullRow(int index, String month) {
        Map<String, BigDecimal> metrics = new java.util.LinkedHashMap<>();
        metrics.put("amount", null);
        return new NormalizedDataRow(Map.of("month", month), metrics, index);
    }
}
