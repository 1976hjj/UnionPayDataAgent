package com.company.paymentanalysis.smartbi;

import com.company.paymentanalysis.controller.ChatQueryController.QueryContext;
import com.company.paymentanalysis.smartbi.SmartBiModels.Filter;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryRequest;
import com.company.paymentanalysis.smartbi.SmartBiModels.RelationNode;
import com.company.paymentanalysis.smartbi.SmartBiModels.Sort;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class SmartBiQueryBuilder {

    private static final Map<String, String> METRIC_FIELDS = Map.of(
            "transactionAmount", "trans_amt",
            "transactionCount", "trans_cnt",
            "successRate", "success_rate");
    private static final Map<String, String> DIMENSION_FIELDS = Map.ofEntries(
            Map.entry("tradeDate", "sett_dt_Day"),
            Map.entry("tradeMonth", "sett_dt_Month2"),
            Map.entry("tradeYear", "sett_dt_Year"),
            Map.entry("region", "region_name"),
            Map.entry("channel", "accept_channel"),
            Map.entry("merchantType", "merchant_type"),
            Map.entry("paymentMethod", "payment_method"));

    private final SmartBiProperties properties;

    public SmartBiQueryBuilder(SmartBiProperties properties) {
        this.properties = properties;
    }

    public QueryRequest build(QueryContext context) {
        if (context == null || !context.hasPeriod() || context.metricIds().isEmpty()) {
            throw new IllegalArgumentException("时间范围和至少一个度量是生成 SmartBI JSON 的必要条件");
        }
        List<String> rows = context.dimensionIds().stream().map(this::dimensionField).toList();
        List<String> columns = context.metricIds().stream().map(this::metricField).toList();
        List<Filter> filters = new ArrayList<>();
        filters.add(new Filter(
                "1", "trade_date", "BETWEEN", List.of(context.startDate(), context.endDate())));
        for (int index = 0; index < context.dimensionFilters().size(); index++) {
            var filter = context.dimensionFilters().get(index);
            filters.add(new Filter(
                    String.valueOf(index + 2),
                    dimensionField(filter.dimensionId()),
                    filter.operator(),
                    filter.values()));
        }
        List<Sort> sorts = context.sorts().stream()
                .map(sort -> new Sort(queryField(sort.fieldId()), sort.direction()))
                .toList();
        return new QueryRequest(
                properties.datasetId(),
                rows,
                columns,
                List.copyOf(filters),
                RelationNode.group("AND", filters.stream().map(RelationNode::leaf).toList()),
                sorts);
    }

    public String metricField(String metricCode) {
        return requiredField(METRIC_FIELDS, metricCode, "度量");
    }

    public String dimensionField(String dimensionCode) {
        return requiredField(DIMENSION_FIELDS, dimensionCode, "维度");
    }

    public String queryField(String code) {
        return METRIC_FIELDS.containsKey(code) ? metricField(code) : dimensionField(code);
    }

    private String requiredField(Map<String, String> fields, String code, String type) {
        String field = fields.get(code);
        if (field == null) {
            throw new IllegalArgumentException("不支持的" + type + "代码：" + code);
        }
        return field;
    }
}
