package com.company.paymentanalysis.strategy.comparison;

import com.company.paymentanalysis.analysis.ComparisonSubject;
import com.company.paymentanalysis.analysis.FilterCondition;
import com.company.paymentanalysis.analysis.QueryPlan;
import com.company.paymentanalysis.execution.AnalysisExecutionResult;
import com.company.paymentanalysis.normalize.NormalizedDataRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

final class ComparisonStrategySupport {

    private ComparisonStrategySupport() {
    }

    static List<NormalizedDataRow> rows(AnalysisExecutionResult result) {
        if (!(result.calculationResult() instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
                .filter(NormalizedDataRow.class::isInstance)
                .map(NormalizedDataRow.class::cast)
                .toList();
    }

    static BigDecimal sumMetric(List<NormalizedDataRow> rows, String metricCode) {
        BigDecimal sum = null;
        for (NormalizedDataRow row : rows) {
            BigDecimal value = row.metrics().get(metricCode);
            if (value != null) {
                sum = sum == null ? value : sum.add(value);
            }
        }
        return sum;
    }

    static BigDecimal subjectValue(
            List<NormalizedDataRow> rows,
            ComparisonSubject subject,
            String dimensionCode,
            String metricCode) {
        BigDecimal sum = null;
        for (NormalizedDataRow row : rows) {
            Object member = row.dimensions().get(dimensionCode);
            if (member != null && matches(member.toString(), subject)) {
                BigDecimal value = row.metrics().get(metricCode);
                if (value != null) {
                    sum = sum == null ? value : sum.add(value);
                }
            }
        }
        return sum;
    }

    static String comparisonDimension(QueryPlan plan) {
        String field = plan.comparisonSubjects().get(0).filters().get(0).field();
        return "tradeDate".equals(field) ? "tradeMonth" : field;
    }

    private static boolean matches(String member, ComparisonSubject subject) {
        String normalizedMember = compact(member);
        if (normalizedMember.equals(compact(subject.label()))) {
            return true;
        }
        for (FilterCondition filter : subject.filters()) {
            if ("BETWEEN".equalsIgnoreCase(filter.operator())
                    && filter.values().size() >= 2
                    && isMonthWithin(member, filter.values().get(0), filter.values().get(1))) {
                return true;
            }
            if (filter.values().stream()
                    .map(ComparisonStrategySupport::compact)
                    .anyMatch(normalizedMember::equals)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMonthWithin(String member, String start, String end) {
        try {
            YearMonth month = parseMonth(member);
            LocalDate first = month.atDay(1);
            LocalDate last = month.atEndOfMonth();
            return !last.isBefore(LocalDate.parse(start))
                    && !first.isAfter(LocalDate.parse(end));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static YearMonth parseMonth(String value) {
        String normalized = value.trim()
                .replace("年", "-")
                .replace("月", "");
        String[] parts = normalized.split("-");
        return YearMonth.of(
                Integer.parseInt(parts[0]),
                Integer.parseInt(parts[1]));
    }

    private static String compact(String value) {
        return value == null
                ? ""
                : value.replace("年", "")
                        .replace("月", "")
                        .replace("-", "")
                        .replace(" ", "")
                        .trim();
    }
}
