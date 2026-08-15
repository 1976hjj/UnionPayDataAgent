package com.company.paymentanalysis.attribution;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.paymentanalysis.query.QueryMetadataCatalog;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class AttributionCatalogTest {

    @Test
    void exposesOnlyRealProductionFieldsAndSelectsSmartBiDerivedMetrics() {
        assertThat(AttributionCatalog.metricIds()).hasSize(24).allMatch(QueryMetadataCatalog::isMetric);
        assertThat(AttributionCatalog.dimensions()).hasSize(71).allSatisfy(dimension -> {
            assertThat(QueryMetadataCatalog.isDimension(dimension.id())).isTrue();
            assertThat(dimension.attributionEnabled()).isTrue();
            assertThat(dimension.description()).startsWith("按").endsWith("分析");
            assertThat(dimension.aliases()).isEmpty();
            assertThat(dimension.mappingHint()).isEmpty();
        });
        assertThat(AttributionCatalog.comparisonMetric(
                        "trans_rmb_amt_m", YearMonth.parse("2026-07"), YearMonth.parse("2026-06")))
                .contains("trans_rmb_amt_hb");
        assertThat(AttributionCatalog.comparisonMetric(
                        "trans_rmb_amt_m", YearMonth.parse("2026-07"), YearMonth.parse("2025-07")))
                .contains("trans_rmb_amt_tb");
        assertThat(AttributionCatalog.comparisonMetric(
                        "trans_rmb_amt_m", YearMonth.parse("2026-07"), YearMonth.parse("2026-05")))
                .isEmpty();
        assertThat(AttributionCatalog.comparisonMetric(
                        "trans_rmb_amt_hb", YearMonth.parse("2026-07"), YearMonth.parse("2026-06")))
                .isEmpty();
    }
}
