package com.company.paymentanalysis.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.paymentanalysis.query.QueryMetadataCatalog;
import com.company.paymentanalysis.smartbi.SmartBiModels.Filter;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryRequest;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryResponse;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MockAttributionSmartBiDataServiceTest {

    private final MockAttributionSmartBiDataService service = new MockAttributionSmartBiDataService();

    @Test
    void coversEveryProductionDimensionAndMetric() {
        Set<String> timeDimensions = Set.of("sett_dt_Year2", "sett_dt_Month2", "sett_dt_Day2");
        assertThat(service.memberCatalog())
                .hasSize(QueryMetadataCatalog.dimensionIds().size() - timeDimensions.size());
        assertThat(service.memberCatalog().values()).allSatisfy(members ->
                assertThat(members).hasSize(4).doesNotHaveDuplicates());

        for (String dimension : QueryMetadataCatalog.dimensionIds()) {
            QueryResponse response = service.query(new QueryRequest(
                    "mock-dataset",
                    List.of(dimension),
                    List.copyOf(QueryMetadataCatalog.metricIds()),
                    List.of(monthFilter("2026-07")),
                    null));

            assertThat(response.data()).as("dimension %s", dimension).isNotEmpty();
            assertThat(response.data()).allSatisfy(row -> {
                assertThat(row).containsKey(dimension);
                assertThat(row.keySet()).containsAll(QueryMetadataCatalog.metricIds());
                QueryMetadataCatalog.metricIds().forEach(metric ->
                        assertThat(row.get(metric)).as("metric %s", metric).isInstanceOf(BigDecimal.class));
            });
        }
    }

    @Test
    void julyDeclineHasAStablePrimaryDriverAndMixedContributions() {
        Map<String, BigDecimal> june = valuesByMember(
                query("2026-06", "acq_ins_ch", "trans_rmb_amt_m"), "acq_ins_ch", "trans_rmb_amt_m");
        Map<String, BigDecimal> july = valuesByMember(
                query("2026-07", "acq_ins_ch", "trans_rmb_amt_m"), "acq_ins_ch", "trans_rmb_amt_m");

        Map<String, BigDecimal> changes = new LinkedHashMap<>();
        june.forEach((member, value) -> changes.put(member, july.get(member).subtract(value)));

        assertThat(july.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .isLessThan(june.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
        assertThat(changes.get("收单机构A")).isNegative();
        assertThat(changes.get("收单机构B")).isPositive();
        assertThat(changes.get("收单机构C")).isNegative();
        assertThat(changes.get("收单机构D")).isPositive();
        assertThat(changes.entrySet().stream()
                        .min(Map.Entry.comparingByValue())
                        .orElseThrow()
                        .getKey())
                .isEqualTo("收单机构A");
    }

    @Test
    void firstLevelFilterDrillsIntoTheSameSyntheticPopulation() {
        Map<String, BigDecimal> june = valuesByMember(
                drilldown("2026-06", "收单机构A", "iss_sc_ch", "trans_rmb_amt_m"),
                "iss_sc_ch",
                "trans_rmb_amt_m");
        Map<String, BigDecimal> july = valuesByMember(
                drilldown("2026-07", "收单机构A", "iss_sc_ch", "trans_rmb_amt_m"),
                "iss_sc_ch",
                "trans_rmb_amt_m");

        assertThat(july).containsOnlyKeys("英国", "中国大陆", "法国", "美国");
        assertThat(july.entrySet()).allSatisfy(entry -> assertThat(entry.getValue()).isPositive());

        Map<String, BigDecimal> changes = new LinkedHashMap<>();
        june.forEach((member, value) -> changes.put(member, july.get(member).subtract(value)));
        assertThat(changes.entrySet().stream()
                        .min(Map.Entry.comparingByValue())
                        .orElseThrow()
                        .getKey())
                .isEqualTo("英国");
    }

    @Test
    void derivesMonthOnMonthAndYearOnYearMetricsFromBaseFacts() {
        QueryResponse response = service.query(new QueryRequest(
                "mock-dataset",
                List.of("sett_dt_Month2"),
                List.of("trans_cnt_m", "trans_cnt_hb", "trans_cnt_tb"),
                List.of(monthFilter("2026-07")),
                null));

        assertThat(response.data()).singleElement().satisfies(row -> {
            assertThat((BigDecimal) row.get("trans_cnt_m")).isPositive();
            assertThat((BigDecimal) row.get("trans_cnt_hb")).isNegative();
            assertThat(row.get("trans_cnt_tb")).isInstanceOf(BigDecimal.class);
        });
        assertThat(response.metadata().get("derivedMetrics")).isEqualTo("materialized SmartBI fields");
    }

    @Test
    void providesAdditionalKnownAnswerScenariosForFutureAgentTests() {
        assertPrimaryChangeDriver("2026-02", "2026-03", "sh_jy_num_m", "收单机构C", false);
        assertPrimaryChangeDriver("2026-07", "2026-08", "trans_rmb_amt_m", "收单机构A", true);
    }

    private QueryResponse query(String period, String dimension, String metric) {
        return service.query(new QueryRequest(
                "mock-dataset",
                List.of(dimension),
                List.of(metric),
                List.of(monthFilter(period)),
                null));
    }

    private QueryResponse drilldown(String period, String institution, String dimension, String metric) {
        return service.query(new QueryRequest(
                "mock-dataset",
                List.of(dimension),
                List.of(metric),
                List.of(
                        monthFilter(period),
                        new Filter("2", "acq_ins_ch", "EQUALS", List.of(institution))),
                null));
    }

    private void assertPrimaryChangeDriver(
            String comparisonPeriod,
            String currentPeriod,
            String metric,
            String expectedDriver,
            boolean positive) {
        Map<String, BigDecimal> comparison = valuesByMember(
                query(comparisonPeriod, "acq_ins_ch", metric), "acq_ins_ch", metric);
        Map<String, BigDecimal> current = valuesByMember(
                query(currentPeriod, "acq_ins_ch", metric), "acq_ins_ch", metric);
        Map<String, BigDecimal> changes = new LinkedHashMap<>();
        comparison.forEach((member, value) -> changes.put(member, current.get(member).subtract(value)));
        Map.Entry<String, BigDecimal> driver = changes.entrySet().stream()
                .max((left, right) -> left.getValue().abs().compareTo(right.getValue().abs()))
                .orElseThrow();
        assertThat(driver.getKey()).isEqualTo(expectedDriver);
        assertThat(driver.getValue().signum()).isEqualTo(positive ? 1 : -1);
    }

    private Map<String, BigDecimal> valuesByMember(
            QueryResponse response, String dimension, String metric) {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        response.data().forEach(row ->
                result.put((String) row.get(dimension), (BigDecimal) row.get(metric)));
        return result;
    }

    private Filter monthFilter(String period) {
        return new Filter("1", "sett_dt_Month2", "EQUALS", List.of(period));
    }
}
