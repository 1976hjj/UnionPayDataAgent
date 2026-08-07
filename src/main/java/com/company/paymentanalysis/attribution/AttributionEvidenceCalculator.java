package com.company.paymentanalysis.attribution;

import com.company.paymentanalysis.attribution.AttributionModels.DimensionFilter;
import com.company.paymentanalysis.attribution.AttributionModels.EffectiveRequest;
import com.company.paymentanalysis.attribution.AttributionModels.Evidence;
import com.company.paymentanalysis.attribution.AttributionModels.MemberEvidence;
import com.company.paymentanalysis.attribution.AttributionModels.OverallEvidence;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class AttributionEvidenceCalculator {

    private static final String PERIOD_FIELD = "sett_dt_Month2";
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal CONSISTENCY_TOLERANCE = new BigDecimal("0.01");

    public OverallEvidence overall(EffectiveRequest request, QueryResponse response) {
        Map<String, Map<String, Object>> byPeriod = indexBy(response.data(), PERIOD_FIELD);
        BigDecimal current = metric(byPeriod, request.currentPeriod(), request.metricId());
        BigDecimal comparison = metric(byPeriod, request.comparisonPeriod(), request.metricId());
        BigDecimal derived = AttributionCatalog.comparisonMetric(
                        request.metricId(),
                        YearMonth.parse(request.currentPeriod()),
                        YearMonth.parse(request.comparisonPeriod()))
                .map(field -> optionalMetric(byPeriod, request.currentPeriod(), field))
                .orElse(null);
        BigDecimal change = current.subtract(comparison);
        return new OverallEvidence(
                current,
                comparison,
                change,
                rate(change, comparison),
                derived,
                direction(change));
    }

    public Evidence evidence(
            EffectiveRequest request,
            OverallEvidence overall,
            String hypothesis,
            String dimensionId,
            int depth,
            List<DimensionFilter> pathFilters,
            QueryResponse response) {
        Map<String, Map<String, Map<String, Object>>> members = new LinkedHashMap<>();
        for (Map<String, Object> row : response.data()) {
            String member = text(row.get(dimensionId));
            String period = text(row.get(PERIOD_FIELD));
            members.computeIfAbsent(member, ignored -> new LinkedHashMap<>()).put(period, row);
        }

        List<MemberEvidence> calculated = new ArrayList<>();
        for (Map.Entry<String, Map<String, Map<String, Object>>> entry : members.entrySet()) {
            BigDecimal current = metric(entry.getValue(), request.currentPeriod(), request.metricId());
            BigDecimal comparison = metric(entry.getValue(), request.comparisonPeriod(), request.metricId());
            BigDecimal change = current.subtract(comparison);
            BigDecimal contribution = overall.changeAmount().signum() == 0
                    ? BigDecimal.ZERO
                    : change.multiply(HUNDRED)
                            .divide(overall.changeAmount(), 4, RoundingMode.HALF_UP);
            calculated.add(new MemberEvidence(
                    0,
                    entry.getKey(),
                    current,
                    comparison,
                    change,
                    rate(change, comparison),
                    contribution,
                    direction(change),
                    aligned(change, overall.changeAmount())));
        }
        calculated.sort(Comparator.comparing(
                        (MemberEvidence member) -> member.changeAmount().abs())
                .reversed());
        List<MemberEvidence> ranked = new ArrayList<>();
        for (int index = 0; index < calculated.size(); index++) {
            MemberEvidence member = calculated.get(index);
            ranked.add(new MemberEvidence(
                    index + 1,
                    member.memberValue(),
                    member.currentValue(),
                    member.comparisonValue(),
                    member.changeAmount(),
                    member.changeRate(),
                    member.contributionRate(),
                    member.direction(),
                    member.alignedWithOverall()));
        }
        List<MemberEvidence> top = ranked.stream().limit(request.topN()).toList();
        MemberEvidence driver = top.stream()
                .filter(MemberEvidence::alignedWithOverall)
                .findFirst()
                .orElse(top.isEmpty() ? null : top.get(0));

        BigDecimal groupedCurrent = ranked.stream()
                .map(MemberEvidence::currentValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal groupedComparison = ranked.stream()
                .map(MemberEvidence::comparisonValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean consistent = close(groupedCurrent, overall.currentValue())
                && close(groupedComparison, overall.comparisonValue());
        if (!consistent) {
            throw new IllegalStateException("维度“" + dimensionId + "”汇总与整体口径不一致");
        }
        BigDecimal coverage = top.stream()
                .filter(MemberEvidence::alignedWithOverall)
                .map(MemberEvidence::contributionRate)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Evidence(
                "evidence-" + UUID.randomUUID(),
                null,
                depth,
                hypothesis,
                dimensionId,
                AttributionCatalog.dimension(dimensionId).name(),
                List.copyOf(pathFilters),
                top,
                driver,
                coverage,
                true);
    }

    private Map<String, Map<String, Object>> indexBy(List<Map<String, Object>> rows, String key) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        rows.forEach(row -> result.put(text(row.get(key)), row));
        return result;
    }

    private BigDecimal metric(
            Map<String, ? extends Map<String, Object>> byPeriod, String period, String field) {
        Map<String, Object> row = byPeriod.get(period);
        if (row == null || row.get(field) == null) {
            throw new IllegalStateException("SmartBI 结果缺少周期 " + period + " 的度量 " + field);
        }
        return decimal(row.get(field));
    }

    private BigDecimal optionalMetric(
            Map<String, ? extends Map<String, Object>> byPeriod, String period, String field) {
        Map<String, Object> row = byPeriod.get(period);
        return row == null || row.get(field) == null ? null : decimal(row.get(field));
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        return new BigDecimal(String.valueOf(value));
    }

    private BigDecimal rate(BigDecimal change, BigDecimal comparison) {
        return comparison.signum() == 0
                ? null
                : change.multiply(HUNDRED).divide(comparison, 4, RoundingMode.HALF_UP);
    }

    private boolean aligned(BigDecimal memberChange, BigDecimal overallChange) {
        return memberChange.signum() != 0 && memberChange.signum() == overallChange.signum();
    }

    private String direction(BigDecimal value) {
        return value.signum() > 0 ? "UP" : value.signum() < 0 ? "DOWN" : "FLAT";
    }

    private boolean close(BigDecimal left, BigDecimal right) {
        return left.subtract(right).abs().compareTo(CONSISTENCY_TOLERANCE) <= 0;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
