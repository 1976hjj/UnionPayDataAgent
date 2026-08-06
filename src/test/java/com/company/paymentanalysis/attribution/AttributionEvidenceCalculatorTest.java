package com.company.paymentanalysis.attribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.paymentanalysis.attribution.AttributionModels.EffectiveRequest;
import com.company.paymentanalysis.attribution.AttributionModels.Evidence;
import com.company.paymentanalysis.attribution.AttributionModels.OverallEvidence;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AttributionEvidenceCalculatorTest {

    private final AttributionEvidenceCalculator calculator = new AttributionEvidenceCalculator();

    @Test
    void javaCalculatesChangeContributionRankingAndConsistencyFromPeriodValues() {
        EffectiveRequest request = request();
        OverallEvidence overall = calculator.overall(request, response(List.of(
                row("2026-06", null, "80", "10"),
                row("2026-07", null, "100", "25"))));

        Evidence evidence = calculator.evidence(
                request,
                overall,
                "测试机构贡献",
                "acq_ins_ch",
                1,
                List.of(),
                response(List.of(
                        row("2026-06", "收单机构A", "40", "0"),
                        row("2026-07", "收单机构A", "60", "50"),
                        row("2026-06", "收单机构B", "40", "0"),
                        row("2026-07", "收单机构B", "40", "0"))));

        assertThat(overall.changeAmount()).isEqualByComparingTo("20");
        assertThat(overall.changeRate()).isEqualByComparingTo("25.0000");
        assertThat(overall.smartBiComparisonRate()).isEqualByComparingTo("25");
        assertThat(evidence.dataConsistent()).isTrue();
        assertThat(evidence.primaryDriver().memberValue()).isEqualTo("收单机构A");
        assertThat(evidence.primaryDriver().changeAmount()).isEqualByComparingTo("20");
        assertThat(evidence.primaryDriver().contributionRate()).isEqualByComparingTo("100.0000");
        assertThat(evidence.members().get(0).rank()).isEqualTo(1);
        assertThat(evidence.topNCoverageRate()).isEqualByComparingTo("100.0000");
    }

    @Test
    void zeroComparisonDoesNotInventAChangeRate() {
        OverallEvidence overall = calculator.overall(request(), response(List.of(
                row("2026-06", null, "0", "0"),
                row("2026-07", null, "10", "0"))));

        assertThat(overall.changeRate()).isNull();
    }

    @Test
    void rejectsDimensionTotalsThatDoNotMatchTheCurrentScope() {
        OverallEvidence overall = new OverallEvidence(
                new BigDecimal("100"), new BigDecimal("80"), new BigDecimal("20"),
                new BigDecimal("25"), new BigDecimal("25"), "UP");

        assertThatThrownBy(() -> calculator.evidence(
                        request(),
                        overall,
                        "口径校验",
                        "acq_ins_ch",
                        1,
                        List.of(),
                        response(List.of(
                                row("2026-06", "收单机构A", "40", "0"),
                                row("2026-07", "收单机构A", "60", "50")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("汇总与整体口径不一致");
    }

    private EffectiveRequest request() {
        return new EffectiveRequest(
                "trans_cnt_m", "2026-07", "2026-06", List.of(), 2, 8, 5, null);
    }

    private QueryResponse response(List<Map<String, Object>> rows) {
        return new QueryResponse("test", rows, Map.of());
    }

    private Map<String, Object> row(String period, String member, String value, String derived) {
        java.util.LinkedHashMap<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("sett_dt_Month2", period);
        if (member != null) {
            row.put("acq_ins_ch", member);
        }
        row.put("trans_cnt_m", new BigDecimal(value));
        row.put("trans_cnt_hb", new BigDecimal(derived));
        return Map.copyOf(row);
    }
}
