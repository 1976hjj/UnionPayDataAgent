package com.company.paymentanalysis.analysis;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class TimeRangeResolver {

    private static final Pattern RECENT_MONTHS =
            Pattern.compile("(?:最近|近)\\s*(\\d{1,2})\\s*个?月");
    private static final Pattern RECENT_DAYS =
            Pattern.compile("(?:最近|近)\\s*(\\d{1,3})\\s*天");
    private static final Pattern EXPLICIT_MONTH =
            Pattern.compile("(?<!\\d)(1[0-2]|[1-9])月");

    public Optional<FilterCondition> resolve(String question, LocalDate currentDate) {
        if (question == null || question.isBlank() || currentDate == null) {
            return Optional.empty();
        }
        Matcher recentMonths = RECENT_MONTHS.matcher(question);
        if (recentMonths.find()) {
            int count = positive(recentMonths.group(1), 24);
            return between(
                    currentDate.minusMonths(count - 1L).withDayOfMonth(1),
                    currentDate);
        }
        Matcher recentDays = RECENT_DAYS.matcher(question);
        if (recentDays.find()) {
            int count = positive(recentDays.group(1), 366);
            return between(currentDate.minusDays(count - 1L), currentDate);
        }
        if (question.contains("今年")) {
            return between(currentDate.withDayOfYear(1), currentDate);
        }
        if (question.contains("上月")) {
            YearMonth previous = YearMonth.from(currentDate).minusMonths(1);
            return between(previous.atDay(1), previous.atEndOfMonth());
        }
        if (question.contains("本月")) {
            return between(currentDate.withDayOfMonth(1), currentDate);
        }
        Matcher explicitMonth = EXPLICIT_MONTH.matcher(question);
        if (explicitMonth.find()) {
            int monthNumber = Integer.parseInt(explicitMonth.group(1));
            YearMonth month = YearMonth.of(currentDate.getYear(), monthNumber);
            return between(month.atDay(1), month.atEndOfMonth());
        }
        return Optional.empty();
    }

    private int positive(String value, int maximum) {
        int parsed = Integer.parseInt(value);
        if (parsed <= 0 || parsed > maximum) {
            throw new IllegalArgumentException("相对时间数量超出允许范围：" + parsed);
        }
        return parsed;
    }

    private Optional<FilterCondition> between(LocalDate start, LocalDate end) {
        return Optional.of(new FilterCondition(
                "tradeDate",
                "BETWEEN",
                List.of(start.toString(), end.toString())));
    }
}
