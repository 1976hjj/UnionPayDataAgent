package com.company.paymentanalysis.time;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves relative time expressions with the server clock, never a model guess. */
public final class RelativeTimeResolver {

    /** Query-time fields are deliberately fixed: date precision must match its filter field. */
    public static final String YEAR_FIELD = "sett_dt_Year2";
    public static final String MONTH_FIELD = "sett_dt_Month2";
    public static final String DAY_FIELD = "sett_dt_Day2";

    private static final Pattern RECENT_N = Pattern.compile("(?:最近|近)\\s*(\\d+)\\s*(天|日|个月|月|年)");
    private static final Pattern EXPLICIT_MONTH = Pattern.compile(
            "(20\\d{2})\\s*(?:年|[-/.])\\s*(0?[1-9]|1[0-2])(?:月)?");
    private static final Pattern MONTH_ONLY = Pattern.compile("(?<!\\d)(1[0-2]|[1-9])月");

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

    /**
     * Resolves the relative-time phrase from the user message, rather than trusting
     * a model's lossy {@code unit + count} representation.  For example, both
     * "今天" and "昨天" used to arrive as DAY + 1, which is not sufficient to
     * determine a date.
     */
    public Optional<ResolvedQueryTime> resolveQueryTime(
            String raw, String semanticKind, String unit, int count) {
        String text = raw == null ? "" : raw.replaceAll("\\s+", "").trim();
        LocalDate today = today();

        if (containsAny(text, "今年至今", "本年至今", "年初至今", "年初到今", "YTD")) {
            return Optional.of(range(DAY_FIELD, today.withDayOfYear(1), today));
        }
        if (containsAny(text, "本月至今", "当月至今", "月初至今", "月初到今", "MTD")) {
            return Optional.of(range(DAY_FIELD, today.withDayOfMonth(1), today));
        }
        if (containsAny(text, "昨天", "昨日")) {
            return Optional.of(single(DAY_FIELD, today.minusDays(1)));
        }
        if (containsAny(text, "前天")) {
            return Optional.of(single(DAY_FIELD, today.minusDays(2)));
        }
        if (containsAny(text, "今天", "今日", "当日")) {
            return Optional.of(single(DAY_FIELD, today));
        }
        if (containsAny(text, "上个月", "上月")) {
            return Optional.of(single(MONTH_FIELD, YearMonth.from(today).minusMonths(1).toString()));
        }
        if (containsAny(text, "本月", "这个月", "当月")) {
            return Optional.of(single(MONTH_FIELD, YearMonth.from(today).toString()));
        }
        if (containsAny(text, "去年")) {
            return Optional.of(single(YEAR_FIELD, Integer.toString(today.getYear() - 1)));
        }
        if (containsAny(text, "今年", "本年", "当年")) {
            return Optional.of(single(YEAR_FIELD, Integer.toString(today.getYear())));
        }

        Matcher recent = RECENT_N.matcher(text);
        if (recent.find()) {
            int parsedCount;
            try {
                parsedCount = Integer.parseInt(recent.group(1));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
            return resolveRecent(recent.group(2), parsedCount);
        }

        return resolveSemanticKind(semanticKind, unit, count);
    }

    /** Parses a query's original user message without requiring an LLM time slot. */
    public Optional<ResolvedQueryTime> resolveQueryTime(String message) {
        return resolveQueryTime(message, "", "", 0);
    }

    private Optional<ResolvedQueryTime> resolveRecent(String unitText, int count) {
        if (count < 1 || count > 10_000) {
            return Optional.empty();
        }
        LocalDate today = today();
        return switch (unitText) {
            case "天", "日" -> Optional.of(count == 1
                    ? single(DAY_FIELD, today)
                    : range(DAY_FIELD, today.minusDays(count - 1L), today));
            case "个月", "月" -> {
                YearMonth end = YearMonth.from(today);
                yield Optional.of(count == 1
                        ? single(MONTH_FIELD, end.toString())
                        : range(MONTH_FIELD, end.minusMonths(count - 1L).toString(), end.toString()));
            }
            case "年" -> Optional.of(count == 1
                    ? single(YEAR_FIELD, Integer.toString(today.getYear()))
                    : range(YEAR_FIELD, Integer.toString(today.getYear() - count + 1), Integer.toString(today.getYear())));
            default -> Optional.empty();
        };
    }

    private Optional<ResolvedQueryTime> resolveSemanticKind(String semanticKind, String unit, int count) {
        String kind = semanticKind == null ? "" : semanticKind.trim().toUpperCase(Locale.ROOT);
        LocalDate today = today();
        return switch (kind) {
            case "TODAY" -> Optional.of(single(DAY_FIELD, today));
            case "YESTERDAY" -> Optional.of(single(DAY_FIELD, today.minusDays(1)));
            case "CURRENT_MONTH_TO_DATE" -> Optional.of(range(DAY_FIELD, today.withDayOfMonth(1), today));
            case "CURRENT_YEAR_TO_DATE" -> Optional.of(range(DAY_FIELD, today.withDayOfYear(1), today));
            default -> resolveRange(unit, count).map(range -> new ResolvedQueryTime(
                    switch (unit == null ? "" : unit.toUpperCase(Locale.ROOT)) {
                        case "DAY" -> DAY_FIELD;
                        case "MONTH" -> MONTH_FIELD;
                        case "YEAR" -> YEAR_FIELD;
                        default -> "";
                    }, range.operator(), range.values()));
        };
    }

    private static boolean containsAny(String text, String... phrases) {
        for (String phrase : phrases) {
            if (text.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    private static ResolvedQueryTime single(String fieldId, LocalDate value) {
        return single(fieldId, value.toString());
    }

    private static ResolvedQueryTime single(String fieldId, String value) {
        return new ResolvedQueryTime(fieldId, "EQUALS", List.of(value));
    }

    private static ResolvedQueryTime range(String fieldId, LocalDate start, LocalDate end) {
        return range(fieldId, start.toString(), end.toString());
    }

    private static ResolvedQueryTime range(String fieldId, String start, String end) {
        return new ResolvedQueryTime(fieldId, "BETWEEN", List.of(start, end));
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
        ResolvedPeriodPair fromMessage = resolveAttributionPeriods(message);
        String current = fromMessage.currentPeriod();
        String comparison = fromMessage.comparisonPeriod();
        if (current == null) current = resolveMonthReference(semanticCurrentPeriod).orElse(null);
        if (comparison == null) comparison = resolveMonthReference(semanticComparisonPeriod).orElse(null);
        return new ResolvedPeriodPair(current, comparison);
    }

    /**
     * Resolves attribution periods directly from the user message.  The template
     * itself is monthly, so expressions that only identify a year are left empty
     * instead of inventing a month.
     */
    public ResolvedPeriodPair resolveAttributionPeriods(String message) {
        String text = message == null ? "" : message.replaceAll("\\s+", "");
        List<YearMonth> explicitMonths = explicitMonths(text);
        if (explicitMonths.size() >= 2) {
            explicitMonths.sort(Comparator.reverseOrder());
            return new ResolvedPeriodPair(explicitMonths.get(0).toString(), explicitMonths.get(1).toString());
        }
        if (explicitMonths.size() == 1) {
            YearMonth current = explicitMonths.get(0);
            return new ResolvedPeriodPair(current.toString(), comparisonForLastYear(text, current));
        }

        String current = resolveLabeled(text, CURRENT_PERIOD_REFERENCE);
        String comparison = resolveLabeled(text, COMPARISON_PERIOD_REFERENCE);
        if (current == null && comparison == null) {
            Matcher pair = PERIOD_COMPARISON.matcher(text);
            if (pair.find()) {
                current = resolveMonthReference(pair.group(1)).orElse(null);
                comparison = resolveMonthReference(pair.group(2)).orElse(null);
            }
        }
        if (current == null && comparison == null && containsAny(text, "去年同期", "上年同期")) {
            YearMonth currentMonth = YearMonth.from(today());
            current = currentMonth.toString();
            comparison = currentMonth.minusYears(1).toString();
        }
        if (current == null && comparison == null) {
            List<YearMonth> monthOnly = monthOnlyReferences(text);
            if (monthOnly.size() >= 2) {
                monthOnly.sort(Comparator.reverseOrder());
                current = monthOnly.get(0).toString();
                comparison = monthOnly.get(1).toString();
            } else if (monthOnly.size() == 1) {
                YearMonth resolved = monthOnly.get(0);
                current = resolved.toString();
                comparison = comparisonForLastYear(text, resolved);
            }
        }
        return new ResolvedPeriodPair(current, comparison);
    }

    private static List<YearMonth> explicitMonths(String text) {
        List<YearMonth> result = new ArrayList<>();
        Matcher matcher = EXPLICIT_MONTH.matcher(text);
        while (matcher.find()) {
            YearMonth month = YearMonth.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
            if (!result.contains(month)) {
                result.add(month);
            }
        }
        return result;
    }

    private List<YearMonth> monthOnlyReferences(String text) {
        List<YearMonth> result = new ArrayList<>();
        Matcher matcher = MONTH_ONLY.matcher(text);
        YearMonth now = YearMonth.from(today());
        while (matcher.find()) {
            int month = Integer.parseInt(matcher.group(1));
            YearMonth candidate = YearMonth.of(now.getYear(), month);
            // A bare month in an analytics request means its latest elapsed occurrence.
            if (candidate.isAfter(now)) {
                candidate = candidate.minusYears(1);
            }
            if (!result.contains(candidate)) {
                result.add(candidate);
            }
        }
        return result;
    }

    private static String comparisonForLastYear(String text, YearMonth current) {
        return containsAny(text, "去年同期", "上年同期") ? current.minusYears(1).toString() : null;
    }

    private String resolveLabeled(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? resolveMonthReference(matcher.group(1)).orElse(null) : null;
    }

    public record ResolvedRange(String operator, List<String> values) {
        public ResolvedRange { values = List.copyOf(values); }
    }

    public record ResolvedQueryTime(String dimensionId, String operator, List<String> values) {
        public ResolvedQueryTime { values = List.copyOf(values); }
    }

    public record ResolvedPeriodPair(String currentPeriod, String comparisonPeriod) {
    }
}
