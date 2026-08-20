package com.company.paymentanalysis.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RelativeTimeResolverTest {

    private final RelativeTimeResolver resolver = new RelativeTimeResolver(
            Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void resolvesLabeledAttributionPeriodsWithoutAskingTheModelToGuessDates() {
        var periods = resolver.resolveAttributionPeriods(
                "", "", "当前周期，本月；对比周期，上月");

        assertThat(periods.currentPeriod()).isEqualTo("2026-08");
        assertThat(periods.comparisonPeriod()).isEqualTo("2026-07");
    }

    @Test
    void prioritizesExplicitAttributionMonthsFromTheOriginalUserMessage() {
        var periods = resolver.resolveAttributionPeriods(
                "2099-12", "2099-11", "分析2026年7月对比2025年7月的交易金额");

        assertThat(periods.currentPeriod()).isEqualTo("2026-07");
        assertThat(periods.comparisonPeriod()).isEqualTo("2025-07");
    }

    @Test
    void resolvesABareMonthToItsLatestElapsedOccurrenceWithoutInventingAComparisonPeriod() {
        var periods = resolver.resolveAttributionPeriods("7月交易额怎么变少了");

        assertThat(periods.currentPeriod()).isEqualTo("2026-07");
        assertThat(periods.comparisonPeriod()).isNull();
    }
}
