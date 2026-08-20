package com.company.paymentanalysis.time;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves relative time expressions with the server clock, never a model guess. */
public final class RelativeTimeResolver {

    /** Query-time fields are deliberately fixed: date precision must match its filter field. */
    public static final String YEAR_FIELD = "sett_dt_Year2";
    public static final String MONTH_FIELD = "sett_dt_Month2";
    public static final String DAY_FIELD = "sett_dt_Day2";

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

    private static boolean containsAny(String text, String... phrases) {
        for (String phrase : phrases) {
            if (text.contains(phrase)) {
                return true;
            }
        }
        return false;
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

    public record ResolvedPeriodPair(String currentPeriod, String comparisonPeriod) {
    }
}
