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
    void resolvesSharedQueryRangesFromTheServerClock() {
        assertThat(resolver.resolveRange("MONTH", 3)).hasValueSatisfying(range -> {
            assertThat(range.operator()).isEqualTo("BETWEEN");
            assertThat(range.values()).containsExactly("2026-06", "2026-08");
        });
        assertThat(resolver.resolveRange("DAY", 1)).hasValueSatisfying(range -> {
            assertThat(range.operator()).isEqualTo("EQUALS");
            assertThat(range.values()).containsExactly("2026-08-16");
        });
    }

    @Test
    void resolvesLabeledAttributionPeriodsWithoutAskingTheModelToGuessDates() {
        var periods = resolver.resolveAttributionPeriods(
                "", "", "当前周期，本月；对比周期，上月");

        assertThat(periods.currentPeriod()).isEqualTo("2026-08");
        assertThat(periods.comparisonPeriod()).isEqualTo("2026-07");
    }
}
