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
    void preservesTheMeaningAndPrecisionOfCommonRelativeDatePhrases() {
        assertThat(resolver.resolveQueryTime("昨天", "", "DAY", 1)).hasValueSatisfying(time -> {
            assertThat(time.dimensionId()).isEqualTo(RelativeTimeResolver.DAY_FIELD);
            assertThat(time.operator()).isEqualTo("EQUALS");
            assertThat(time.values()).containsExactly("2026-08-15");
        });
        assertThat(resolver.resolveQueryTime("今年至今", "", "YEAR", 1)).hasValueSatisfying(time -> {
            assertThat(time.dimensionId()).isEqualTo(RelativeTimeResolver.DAY_FIELD);
            assertThat(time.operator()).isEqualTo("BETWEEN");
            assertThat(time.values()).containsExactly("2026-01-01", "2026-08-16");
        });
        assertThat(resolver.resolveQueryTime("本月至今", "", "MONTH", 1)).hasValueSatisfying(time -> {
            assertThat(time.dimensionId()).isEqualTo(RelativeTimeResolver.DAY_FIELD);
            assertThat(time.values()).containsExactly("2026-08-01", "2026-08-16");
        });
        assertThat(resolver.resolveQueryTime("最近3天", "", "DAY", 3)).hasValueSatisfying(time -> {
            assertThat(time.dimensionId()).isEqualTo(RelativeTimeResolver.DAY_FIELD);
            assertThat(time.values()).containsExactly("2026-08-14", "2026-08-16");
        });
    }

    @Test
    void canonicalizesExplicitMonthComparisonWithoutAskingTheModelForAFieldId() {
        assertThat(resolver.resolveQueryTime("2026.3对比2026.4月")).hasValueSatisfying(time -> {
            assertThat(time.dimensionId()).isEqualTo(RelativeTimeResolver.MONTH_FIELD);
            assertThat(time.operator()).isEqualTo("IN");
            assertThat(time.values()).containsExactly("2026-03", "2026-04");
        });
    }

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
