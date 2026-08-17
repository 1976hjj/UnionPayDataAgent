package com.company.paymentanalysis.time;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves relative time expressions with the server clock, never a model guess. */
public final class RelativeTimeResolver {

    private static final String MONTH_REFERENCE =
            "本月|这个月|当月|上月|上个月|上期|上上月|前两个月|前2个月";
    private static final Pattern CURRENT_PERIOD_REFERENCE = Pattern.compile(
            "(?:当前期|当前周期|本期)\\s*[：:,，;；]?\\s*(" + MONTH_REFERENCE + ")");
    private static final Pattern COMPARISON_PERIOD_REFERENCE = Pattern.compile(
            "(?:对比期|对比周期|上期)\\s*[：:,，;；]?\\s*(" + MONTH_REFERENCE + ")");
    private static final Pattern PERIOD_COMPARISON = Pattern.compile(
            "(" + MONTH_REFERENCE + ")\\s*(?:对比|较|相比|比)\\s*(" + MONTH_REFERENCE + ")");

    private final Clock clock;

    public RelativeTimeResolver(Clock clock) {
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public Optional<ResolvedRange> resolveRange(String unit, int count) {
        if (count < 1 || count > 10_000) return Optional.empty();
        LocalDate today = today();
        List<String> values = switch (unit == null ? "" : unit.toUpperCase(Locale.ROOT)) {
            case "DAY" -> count == 1
                    ? List.of(today.toString())
                    : List.of(today.minusDays(count - 1L).toString(), today.toString());
            case "MONTH" -> {
                YearMonth end = YearMonth.from(today);
                yield count == 1
                        ? List.of(end.toString())
                        : List.of(end.minusMonths(count - 1L).toString(), end.toString());
            }
            case "YEAR" -> count == 1
                    ? List.of(Integer.toString(today.getYear()))
                    : List.of(Integer.toString(today.getYear() - count + 1), Integer.toString(today.getYear()));
            default -> List.of();
        };
        return values.isEmpty()
                ? Optional.empty()
                : Optional.of(new ResolvedRange(values.size() == 1 ? "EQUALS" : "BETWEEN", values));
    }

    public Optional<String> resolveMonthReference(String value) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", "").trim();
        int offset = switch (normalized) {
            case "本月", "这个月", "当月" -> 0;
            case "上月", "上个月", "上期" -> -1;
            case "上上月", "前两个月", "前2个月" -> -2;
            default -> Integer.MIN_VALUE;
        };
        return offset == Integer.MIN_VALUE
                ? Optional.empty()
                : Optional.of(YearMonth.from(today()).plusMonths(offset).toString());
    }

    public ResolvedPeriodPair resolveAttributionPeriods(
            String semanticCurrentPeriod, String semanticComparisonPeriod, String message) {
        String current = resolveMonthReference(semanticCurrentPeriod).orElse(null);
        String comparison = resolveMonthReference(semanticComparisonPeriod).orElse(null);
        String text = message == null ? "" : message.replaceAll("\\s+", "");
        if (current == null) current = resolveLabeled(text, CURRENT_PERIOD_REFERENCE);
        if (comparison == null) comparison = resolveLabeled(text, COMPARISON_PERIOD_REFERENCE);
        if (current == null && comparison == null) {
            Matcher pair = PERIOD_COMPARISON.matcher(text);
            if (pair.find()) {
                current = resolveMonthReference(pair.group(1)).orElse(null);
                comparison = resolveMonthReference(pair.group(2)).orElse(null);
            }
        }
        return new ResolvedPeriodPair(current, comparison);
    }

    private String resolveLabeled(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? resolveMonthReference(matcher.group(1)).orElse(null) : null;
    }

    public record ResolvedRange(String operator, List<String> values) {
        public ResolvedRange { values = List.copyOf(values); }
    }

    public record ResolvedPeriodPair(String currentPeriod, String comparisonPeriod) {
    }
}
