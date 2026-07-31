package com.company.paymentanalysis.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class TimeRangeResolverTest {

    private final TimeRangeResolver resolver = new TimeRangeResolver();
    private final LocalDate currentDate = LocalDate.of(2026, 7, 31);

    @Test
    void resolvesRelativeRangesFromFixedCurrentDate() {
        assertRange("最近6个月交易金额走势", "2026-02-01", "2026-07-31");
        assertRange("最近30天交易笔数变化", "2026-07-02", "2026-07-31");
        assertRange("今年每个月交易笔数", "2026-01-01", "2026-07-31");
        assertRange("本月交易金额", "2026-07-01", "2026-07-31");
        assertRange("上月交易金额", "2026-06-01", "2026-06-30");
        assertRange("查询6月交易金额", "2026-06-01", "2026-06-30");
    }

    private void assertRange(String question, String start, String end) {
        FilterCondition filter = resolver.resolve(question, currentDate).orElseThrow();
        assertThat(filter.field()).isEqualTo("tradeDate");
        assertThat(filter.operator()).isEqualTo("BETWEEN");
        assertThat(filter.values()).containsExactly(start, end);
    }
}
