package com.company.paymentanalysis.controller;

import com.company.paymentanalysis.smartbi.SmartBiModels.Filter;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryRequest;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryResponse;
import com.company.paymentanalysis.query.QueryMetadataCatalog;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class MockChatSmartBiDataService {

    private static final Set<String> METRICS = QueryMetadataCatalog.metricIds();
    private static final Set<String> LEGACY_METRICS =
            Set.of("acpt_trans_rmb_amt_m", "trans_amt", "trans_cnt", "success_rate");
    private static final Map<String, List<String>> MEMBERS = Map.ofEntries(
            Map.entry("acq_mkt_ch", List.of("英国", "法国", "德国", "中国")),
            Map.entry("iss_sc_ch", List.of("境内发卡", "亚太地区", "欧洲地区", "北美地区")),
            Map.entry("acq_ins_ch", List.of("收单机构A", "收单机构B", "收单机构C", "收单机构D")),
            Map.entry("region_name", List.of("华东", "华南", "华北", "西南")),
            Map.entry("accept_channel", List.of("线上渠道", "线下渠道", "移动端", "其他渠道")),
            Map.entry("sett_dt_Year2", List.of("2025", "2026")),
            Map.entry("sett_dt_Month2", List.of("2026-05", "2026-06", "2026-07", "2026-08")),
            Map.entry("sett_dt_Day2", List.of("2026-08-01", "2026-08-02", "2026-08-03", "2026-08-04")),
            Map.entry("sett_dt_Day", List.of("2026-07-27", "2026-07-28", "2026-07-29", "2026-07-30")),
            Map.entry("reg_nm_lvl_1", List.of("上海", "北京", "广东", "浙江")),
            Map.entry("brand", List.of("银联", "Visa", "Mastercard", "其他")),
            Map.entry("merchant_type", List.of("零售", "餐饮", "交通", "生活服务")),
            Map.entry("payment_method", List.of("银行卡", "云闪付", "二维码", "其他")));

    public boolean supports(QueryRequest request) {
        if (request == null || request.columns() == null || request.columns().isEmpty()) {
            return false;
        }
        boolean legacy = request.columns().stream().allMatch(LEGACY_METRICS::contains)
                && request.rows().stream().allMatch(MEMBERS::containsKey);
        boolean production = request.columns().stream().allMatch(METRICS::contains)
                && request.rows().stream().allMatch(QueryMetadataCatalog::isDimension)
                && request.filters().stream().allMatch(filter -> QueryMetadataCatalog.isDimension(filter.name()));
        return legacy || production;
    }

    public QueryResponse query(QueryRequest request) {
        List<Map<String, Object>> rows = request.rows().isEmpty()
                ? List.of(aggregateRow(request))
                : groupedRows(request);
        rows = sortedRows(request, rows);
        return new QueryResponse(
                "mock-chat-smartbi-" + UUID.randomUUID(),
                rows,
                Map.of(
                        "source", "Mock SmartBI chat dataset",
                        "rowCount", rows.size()));
    }

    private Map<String, Object> aggregateRow(QueryRequest request) {
        Map<String, Object> row = new LinkedHashMap<>();
        request.columns().forEach(metric -> row.put(metric, aggregateValue(request, metric)));
        return Map.copyOf(row);
    }

    private List<Map<String, Object>> sortedRows(
            QueryRequest request, List<Map<String, Object>> rows) {
        if (request.sorts().isEmpty() || rows.size() < 2) {
            return rows;
        }
        Comparator<Map<String, Object>> comparator = null;
        for (var sort : request.sorts()) {
            Comparator<Map<String, Object>> next =
                    Comparator.comparing(row -> comparable(row.get(sort.field())),
                            Comparator.nullsLast(Comparator.naturalOrder()));
            if ("DESC".equals(sort.direction())) {
                next = next.reversed();
            }
            comparator = comparator == null ? next : comparator.thenComparing(next);
        }
        return rows.stream().sorted(comparator).toList();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Comparable comparable(Object value) {
        return value instanceof Comparable comparable ? comparable : value == null ? null : value.toString();
    }

    private List<Map<String, Object>> groupedRows(QueryRequest request) {
        List<String> firstDimensionMembers = selectedMembers(request, request.rows().get(0));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int index = 0; index < firstDimensionMembers.size(); index++) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (String dimension : request.rows()) {
                List<String> members = selectedMembers(request, dimension);
                row.put(dimension, members.get(Math.min(index, members.size() - 1)));
            }
            int rowIndex = index;
            request.columns().forEach(metric -> row.put(metric, metricValue(metric, rowIndex)));
            rows.add(row);
        }
        return List.copyOf(rows);
    }

    private List<String> selectedMembers(QueryRequest request, String dimension) {
        List<String> available = MEMBERS.getOrDefault(
                dimension,
                List.of(
                        QueryMetadataCatalog.displayName(dimension) + "A",
                        QueryMetadataCatalog.displayName(dimension) + "B",
                        QueryMetadataCatalog.displayName(dimension) + "C",
                        QueryMetadataCatalog.displayName(dimension) + "D"));
        List<String> rangedMembers = membersForRangeBounds(dimension, request.filters());
        if (!rangedMembers.isEmpty()) {
            return rangedMembers;
        }
        List<String> requested = request.filters().stream()
                .filter(filter -> filter.name().equals(dimension)
                        || "sett_dt_Month2".equals(dimension)
                                && Set.of("trade_date", "sett_dt_Year").contains(filter.name())
                        || "sett_dt_Day2".equals(dimension)
                                && "trade_date".equals(filter.name()))
                .flatMap(filter -> membersForFilter(dimension, filter).stream())
                .distinct()
                .toList();
        return requested.isEmpty() ? available : requested;
    }

    /**
     * The SmartBI REST protocol represents an inclusive range as two documented
     * predicates on the same field.  The mock expands that pair only so its
     * generated date rows resemble a real grouped result; production requests
     * are sent through unchanged.
     */
    private List<String> membersForRangeBounds(String dimension, List<Filter> filters) {
        if (!Set.of("sett_dt_Month2", "sett_dt_Day", "sett_dt_Day2").contains(dimension)) {
            return List.of();
        }

        String sourceField = "sett_dt_Day".equals(dimension) ? "trade_date" : dimension;
        String lowerBound = filters.stream()
                .filter(filter -> sourceField.equals(filter.name()))
                .filter(filter -> "GREATER_EQUALS".equalsIgnoreCase(filter.operation()))
                .map(Filter::values)
                .filter(values -> !values.isEmpty())
                .map(values -> values.get(0))
                .findFirst()
                .orElse(null);
        String upperBound = filters.stream()
                .filter(filter -> sourceField.equals(filter.name()))
                .filter(filter -> "LESS_EQUALS".equalsIgnoreCase(filter.operation()))
                .map(Filter::values)
                .filter(values -> !values.isEmpty())
                .map(values -> values.get(0))
                .findFirst()
                .orElse(null);
        if (lowerBound == null || upperBound == null) {
            return List.of();
        }

        if ("sett_dt_Month2".equals(dimension)) {
            YearMonth start = YearMonth.parse(lowerBound.substring(0, 7));
            YearMonth end = YearMonth.parse(upperBound.substring(0, 7));
            return java.util.stream.Stream.iterate(
                            start, month -> !month.isAfter(end), month -> month.plusMonths(1))
                    .map(YearMonth::toString)
                    .toList();
        }

        LocalDate start = LocalDate.parse(lowerBound);
        LocalDate end = LocalDate.parse(upperBound);
        return start.datesUntil(end.plusDays(1)).map(LocalDate::toString).toList();
    }

    private List<String> membersForFilter(String dimension, Filter filter) {
        if ("sett_dt_Month2".equals(dimension)
                && "sett_dt_Year".equals(filter.name())
                && !filter.values().isEmpty()) {
            String year = filter.values().get(0);
            return java.util.stream.IntStream.rangeClosed(1, 12)
                    .mapToObj(month -> year + "-" + String.format("%02d", month))
                    .toList();
        }
        if ("sett_dt_Month2".equals(dimension)
                && "BETWEEN".equalsIgnoreCase(filter.operation())
                && filter.values().size() >= 2) {
            YearMonth start = YearMonth.parse(filter.values().get(0).substring(0, 7));
            YearMonth end = YearMonth.parse(filter.values().get(1).substring(0, 7));
            return java.util.stream.Stream.iterate(
                            start, month -> !month.isAfter(end), month -> month.plusMonths(1))
                    .map(YearMonth::toString)
                    .toList();
        }
        if (Set.of("sett_dt_Day", "sett_dt_Day2").contains(dimension)
                && "BETWEEN".equalsIgnoreCase(filter.operation())
                && filter.values().size() >= 2) {
            LocalDate start = LocalDate.parse(filter.values().get(0));
            LocalDate end = LocalDate.parse(filter.values().get(1));
            return start.datesUntil(end.plusDays(1)).map(LocalDate::toString).toList();
        }
        return filter.values();
    }

    private BigDecimal aggregateValue(QueryRequest request, String metric) {
        int adjustment = Math.abs(request.filters().toString().hashCode() % 10_000);
        return base(metric).add(BigDecimal.valueOf(adjustment));
    }

    private BigDecimal metricValue(String metric, int index) {
        BigDecimal[] factors = {
            new BigDecimal("1.00"),
            new BigDecimal("0.82"),
            new BigDecimal("1.18"),
            new BigDecimal("0.64")
        };
        return base(metric).multiply(factors[index % factors.length]);
    }

    private BigDecimal base(String metric) {
        return switch (metric) {
            case "trans_cnt", "trans_cnt_m", "acpt_cnt_m" -> new BigDecimal("526400");
            case "success_rate" -> new BigDecimal("98.12");
            default -> new BigDecimal("52640000");
        };
    }
}
