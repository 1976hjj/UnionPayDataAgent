package com.company.paymentanalysis.calculation;

import com.company.paymentanalysis.normalize.NormalizedDataRow;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class DefaultAnalysisCalculationEngine implements AnalysisCalculationEngine {

    @Override
    public ComparisonResult compare(
            BigDecimal subjectA,
            BigDecimal subjectB,
            ComparisonRequest request) {
        if (subjectA == null || subjectB == null) {
            throw new IllegalArgumentException("对比对象的值不能为空，缺失值不能按 0 计算");
        }
        if (request == null) {
            throw new IllegalArgumentException("ComparisonRequest 不能为空");
        }

        BigDecimal signedDifference = subjectA.subtract(subjectB);
        BigDecimal displayDifference = switch (request.expression()) {
            case B_MINUS_A, A_LESS_THAN_B -> subjectB.subtract(subjectA);
            case A_MINUS_B, A_MORE_THAN_B, COMPARE_ONLY -> signedDifference;
        };
        ComparisonRelation relation = relation(subjectA, subjectB);
        List<String> warnings = new ArrayList<>();
        BigDecimal changeRate = null;
        if (request.calculations().contains(CalculationType.CHANGE_RATE)) {
            if (subjectB.compareTo(BigDecimal.ZERO) == 0) {
                warnings.add("基期为0，无法计算常规变化率");
            } else {
                BigDecimal numerator = request.expression() == ComparisonExpression.A_LESS_THAN_B
                        || request.expression() == ComparisonExpression.B_MINUS_A
                        ? subjectB.subtract(subjectA)
                        : signedDifference;
                changeRate = numerator.divide(
                        subjectB,
                        request.rateScale(),
                        request.roundingMode());
            }
        }
        String formula = request.subjectALabel() + "-"
                + request.subjectBLabel() + " = "
                + subjectA.toPlainString() + "-"
                + subjectB.toPlainString() + " = "
                + signedDifference.toPlainString();
        return new ComparisonResult(
                request.subjectALabel(),
                request.subjectBLabel(),
                subjectA,
                subjectB,
                signedDifference,
                displayDifference,
                changeRate,
                relation,
                formula,
                warnings);
    }

    @Override
    public RankingResult rank(List<NormalizedDataRow> rows, RankingRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("RankingRequest 不能为空");
        }
        List<NormalizedDataRow> source = rows == null ? List.of() : rows;
        List<String> warnings = new ArrayList<>();
        List<RankCandidate> candidates = new ArrayList<>();
        int nullCount = 0;

        for (NormalizedDataRow row : source) {
            if (!row.metrics().containsKey(request.metricField())) {
                throw new IllegalArgumentException("标准化结果缺少排名指标：" + request.metricField());
            }
            BigDecimal value = row.metrics().get(request.metricField());
            if (value == null) {
                nullCount++;
                if (request.nullValuePolicy() == NullValuePolicy.ERROR) {
                    throw new IllegalArgumentException("排名指标存在 null 值");
                }
                if (request.nullValuePolicy() == NullValuePolicy.EXCLUDE) {
                    continue;
                }
                value = BigDecimal.ZERO;
            }
            candidates.add(new RankCandidate(row, value));
        }

        if (nullCount > 0) {
            String policy = request.nullValuePolicy() == NullValuePolicy.EXCLUDE
                    ? "已排除"
                    : "已按 0 处理";
            warnings.add(nullCount + " 行排名指标为 null，" + policy);
        }

        Comparator<RankCandidate> comparator =
                Comparator.comparing(RankCandidate::value);
        if (request.direction() == SortDirection.DESC) {
            comparator = comparator.reversed();
        }
        comparator = comparator.thenComparingInt(candidate -> candidate.row().originalIndex());
        candidates.sort(comparator);

        int resultCount = Math.min(request.limit(), candidates.size());
        if (resultCount < request.limit()) {
            warnings.add("有效结果不足 " + request.limit() + " 条，实际返回 " + resultCount + " 条");
        }
        List<RankingItem> items = new ArrayList<>(resultCount);
        for (int index = 0; index < resultCount; index++) {
            RankCandidate candidate = candidates.get(index);
            items.add(new RankingItem(
                    index + 1,
                    candidate.row().dimensions(),
                    candidate.value()));
        }
        return new RankingResult(
                items,
                request.limit(),
                items.size(),
                request.direction(),
                warnings);
    }

    @Override
    public TrendSummary summarizeTrend(
            List<NormalizedDataRow> rows,
            TrendRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("TrendRequest 不能为空");
        }
        List<NormalizedDataRow> source = rows == null ? List.of() : rows;
        List<String> warnings = new ArrayList<>();
        List<TrendPoint> points = new ArrayList<>();
        Set<LocalDate> periods = new HashSet<>();

        for (NormalizedDataRow row : source) {
            if (!row.dimensions().containsKey(request.timeField())) {
                throw new IllegalArgumentException("标准化结果缺少趋势时间字段：" + request.timeField());
            }
            if (!row.metrics().containsKey(request.metricField())) {
                throw new IllegalArgumentException("标准化结果缺少趋势指标：" + request.metricField());
            }
            Object rawPeriod = row.dimensions().get(request.timeField());
            if (rawPeriod == null || rawPeriod.toString().isBlank()) {
                throw new IllegalArgumentException("趋势时间字段不能为空");
            }
            BigDecimal value = row.metrics().get(request.metricField());
            if (value == null) {
                warnings.add("原始第 " + row.originalIndex() + " 行指标为 null，已跳过");
                continue;
            }
            String label = rawPeriod.toString().trim();
            LocalDate periodStart = parsePeriod(label, request.granularity());
            if (!periods.add(periodStart)) {
                throw new IllegalArgumentException("趋势结果存在重复时间点：" + label);
            }
            points.add(new TrendPoint(label, periodStart, value));
        }

        points.sort(Comparator.comparing(TrendPoint::periodStart));
        warnMissingPeriods(points, request.granularity(), warnings);
        if (request.sortDirection() == SortDirection.DESC) {
            points.sort(Comparator.comparing(TrendPoint::periodStart).reversed());
        }

        List<TrendPoint> chronological = points.stream()
                .sorted(Comparator.comparing(TrendPoint::periodStart))
                .toList();
        if (chronological.isEmpty()) {
            return new TrendSummary(
                    points, null, null, null, null, null, null,
                    TrendDirection.UNKNOWN, 0, warnings);
        }

        TrendPoint first = chronological.get(0);
        TrendPoint last = chronological.get(chronological.size() - 1);
        TrendPoint max = chronological.stream()
                .max(Comparator.comparing(TrendPoint::value))
                .orElseThrow();
        TrendPoint min = chronological.stream()
                .min(Comparator.comparing(TrendPoint::value))
                .orElseThrow();
        BigDecimal totalDifference = last.value().subtract(first.value());
        BigDecimal totalChangeRate = null;
        if (first.value().compareTo(BigDecimal.ZERO) == 0) {
            warnings.add("首期值为0，无法计算累计变化率");
        } else {
            totalChangeRate = totalDifference.divide(
                    first.value(),
                    request.rateScale(),
                    java.math.RoundingMode.HALF_UP);
        }
        return new TrendSummary(
                points,
                first,
                last,
                max,
                min,
                totalDifference,
                totalChangeRate,
                trendDirection(chronological),
                chronological.size(),
                warnings);
    }

    private ComparisonRelation relation(BigDecimal subjectA, BigDecimal subjectB) {
        int comparison = subjectA.compareTo(subjectB);
        if (comparison > 0) {
            return ComparisonRelation.A_GREATER_THAN_B;
        }
        if (comparison < 0) {
            return ComparisonRelation.A_LESS_THAN_B;
        }
        return ComparisonRelation.EQUAL;
    }

    private TrendDirection trendDirection(List<TrendPoint> points) {
        if (points.size() <= 1) {
            return TrendDirection.UNKNOWN;
        }
        boolean increased = false;
        boolean decreased = false;
        for (int index = 1; index < points.size(); index++) {
            int comparison = points.get(index).value().compareTo(points.get(index - 1).value());
            increased |= comparison > 0;
            decreased |= comparison < 0;
        }
        if (!increased && !decreased) {
            return TrendDirection.FLAT;
        }
        if (increased && !decreased) {
            return TrendDirection.UP;
        }
        if (!increased) {
            return TrendDirection.DOWN;
        }
        return TrendDirection.MIXED;
    }

    private LocalDate parsePeriod(String label, TimeGranularity granularity) {
        try {
            return switch (granularity) {
                case DAY -> LocalDate.parse(label, DateTimeFormatter.ISO_LOCAL_DATE);
                case WEEK -> parseWeek(label);
                case MONTH -> parseMonth(label);
                case QUARTER -> parseQuarter(label);
                case YEAR -> parseYear(label);
            };
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException(
                    "非法的" + granularity + "时间格式：" + label,
                    exception);
        }
    }

    private LocalDate parseWeek(String label) {
        if (!label.matches("\\d{4}-W\\d{2}")) {
            throw new DateTimeParseException("周格式应为 yyyy-Www", label, 0);
        }
        int year = Integer.parseInt(label.substring(0, 4));
        int week = Integer.parseInt(label.substring(6));
        return LocalDate.of(year, 1, 4)
                .with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, week)
                .with(java.time.DayOfWeek.MONDAY);
    }

    private LocalDate parseMonth(String label) {
        if (label.matches("\\d{4}年\\d{1,2}月")) {
            int yearEnd = label.indexOf('年');
            int year = Integer.parseInt(label.substring(0, yearEnd));
            int month = Integer.parseInt(label.substring(yearEnd + 1, label.length() - 1));
            return YearMonth.of(year, month).atDay(1);
        }
        return YearMonth.parse(label, DateTimeFormatter.ofPattern("yyyy-MM")).atDay(1);
    }

    private LocalDate parseQuarter(String label) {
        if (!label.matches("\\d{4}-Q[1-4]")) {
            throw new DateTimeParseException("季度格式应为 yyyy-Qn", label, 0);
        }
        int year = Integer.parseInt(label.substring(0, 4));
        int quarter = Integer.parseInt(label.substring(6));
        return LocalDate.of(year, (quarter - 1) * 3 + 1, 1);
    }

    private LocalDate parseYear(String label) {
        String normalized = label.endsWith("年")
                ? label.substring(0, label.length() - 1)
                : label;
        return Year.parse(normalized).atDay(1);
    }

    private void warnMissingPeriods(
            List<TrendPoint> points,
            TimeGranularity granularity,
            List<String> warnings) {
        if (points.size() < 2) {
            return;
        }
        List<TrendPoint> ordered = points.stream()
                .sorted(Comparator.comparing(TrendPoint::periodStart))
                .toList();
        for (int index = 1; index < ordered.size(); index++) {
            LocalDate previous = ordered.get(index - 1).periodStart();
            LocalDate current = ordered.get(index).periodStart();
            if (periodDistance(previous, current, granularity) > 1) {
                warnings.add(
                        ordered.get(index - 1).periodLabel()
                                + " 与 "
                                + ordered.get(index).periodLabel()
                                + " 之间存在缺失时间点，未自动补 0");
            }
        }
    }

    private long periodDistance(
            LocalDate previous,
            LocalDate current,
            TimeGranularity granularity) {
        return switch (granularity) {
            case DAY -> ChronoUnit.DAYS.between(previous, current);
            case WEEK -> ChronoUnit.WEEKS.between(previous, current);
            case MONTH -> ChronoUnit.MONTHS.between(
                    YearMonth.from(previous),
                    YearMonth.from(current));
            case QUARTER -> ChronoUnit.MONTHS.between(
                    YearMonth.from(previous),
                    YearMonth.from(current)) / 3;
            case YEAR -> ChronoUnit.YEARS.between(previous, current);
        };
    }

    private record RankCandidate(NormalizedDataRow row, BigDecimal value) {
    }
}
