package com.company.paymentanalysis.controller;

import com.company.paymentanalysis.smartbi.SmartBiModels.Filter;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryRequest;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryResponse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class MockChatSmartBiDataService {

    private static final Set<String> METRICS =
            Set.of("acpt_trans_rmb_amt_m", "trans_amt", "trans_cnt", "success_rate");
    private static final Map<String, List<String>> MEMBERS = Map.ofEntries(
            Map.entry("acq_mkt_ch", List.of("英国", "法国", "德国", "中国")),
            Map.entry("region_name", List.of("华东", "华南", "华北", "西南")),
            Map.entry("accept_channel", List.of("线上渠道", "线下渠道", "移动端", "其他渠道")),
            Map.entry("sett_dt_Year", List.of("2025", "2026")),
            Map.entry("sett_dt_Month2", List.of("2026-05", "2026-06", "2026-07")),
            Map.entry("sett_dt_Day", List.of("2026-07-27", "2026-07-28", "2026-07-29", "2026-07-30")),
            Map.entry("merchant_type", List.of("零售", "餐饮", "交通", "生活服务")),
            Map.entry("payment_method", List.of("银行卡", "云闪付", "二维码", "其他")));

    public boolean supports(QueryRequest request) {
        return request != null
                && request.columns() != null
                && request.columns().size() == 1
                && METRICS.contains(request.columns().get(0))
                && request.rows().stream().allMatch(MEMBERS::containsKey);
    }

    public QueryResponse query(QueryRequest request) {
        String metric = request.columns().get(0);
        List<Map<String, Object>> rows = request.rows().isEmpty()
                ? List.of(Map.of(metric, aggregateValue(request, metric)))
                : groupedRows(request, metric);
        return new QueryResponse(
                "mock-chat-smartbi-" + UUID.randomUUID(),
                rows,
                Map.of(
                        "source", "Mock SmartBI chat dataset",
                        "rowCount", rows.size()));
    }

    private List<Map<String, Object>> groupedRows(QueryRequest request, String metric) {
        List<String> firstDimensionMembers = selectedMembers(request, request.rows().get(0));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int index = 0; index < firstDimensionMembers.size(); index++) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (String dimension : request.rows()) {
                List<String> members = selectedMembers(request, dimension);
                row.put(dimension, members.get(Math.min(index, members.size() - 1)));
            }
            row.put(metric, metricValue(metric, index));
            rows.add(row);
        }
        return List.copyOf(rows);
    }

    private List<String> selectedMembers(QueryRequest request, String dimension) {
        List<String> available = MEMBERS.get(dimension);
        List<String> requested = request.filters().stream()
                .filter(filter -> filter.name().equals(dimension)
                        || "sett_dt_Month2".equals(dimension)
                                && "trade_date".equals(filter.name()))
                .flatMap(filter -> membersForFilter(dimension, filter).stream())
                .distinct()
                .toList();
        return requested.isEmpty() ? available : requested;
    }

    private List<String> membersForFilter(String dimension, Filter filter) {
        if ("sett_dt_Month2".equals(dimension)
                && "BETWEEN".equalsIgnoreCase(filter.operation())
                && filter.values().size() >= 2) {
            String startMonth = filter.values().get(0).substring(0, 7);
            String endMonth = filter.values().get(1).substring(0, 7);
            return MEMBERS.get(dimension).stream()
                    .filter(month -> month.compareTo(startMonth) >= 0
                            && month.compareTo(endMonth) <= 0)
                    .toList();
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
            case "trans_cnt" -> new BigDecimal("526400");
            case "success_rate" -> new BigDecimal("98.12");
            default -> new BigDecimal("52640000");
        };
    }
}
