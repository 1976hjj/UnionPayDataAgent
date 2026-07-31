package com.company.paymentanalysis.smartbi;

import com.company.paymentanalysis.analysis.ComparisonSubject;
import com.company.paymentanalysis.analysis.FilterCondition;
import com.company.paymentanalysis.analysis.IntentType;
import com.company.paymentanalysis.analysis.QueryPlan;
import com.company.paymentanalysis.smartbi.SmartBiModels.Filter;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryRequest;
import com.company.paymentanalysis.smartbi.SmartBiModels.RelationNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
public class SmartBiQueryBuilder {

    private static final Map<String, String> METRIC_FIELDS = Map.of(
            "rmbAmount", "acpt_trans_rmb_amt_m",
            "transactionAmount", "trans_amt",
            "transactionCount", "trans_cnt",
            "successRate", "success_rate");

    private static final Map<String, String> DIMENSION_FIELDS = Map.ofEntries(
            Map.entry("acquiringRegion", "acq_mkt_ch"),
            Map.entry("issuingRegion", "iss_mkt_ch"),
            Map.entry("acquiringInstitution", "acq_ins_ch"),
            Map.entry("region", "region_name"),
            Map.entry("channel", "accept_channel"),
            Map.entry("tradeYear", "sett_dt_Year"),
            Map.entry("tradeMonth", "sett_dt_Month2"),
            Map.entry("tradeDate", "sett_dt_Day"),
            Map.entry("merchantType", "merchant_type"),
            Map.entry("paymentMethod", "payment_method"));

    private static final Map<String, String> FILTER_FIELDS = Map.ofEntries(
            Map.entry("tradeDate", "trade_date"),
            Map.entry("tradeMonth", "sett_dt_Month2"),
            Map.entry("tradeYear", "sett_dt_Year"),
            Map.entry("acquiringRegion", "acq_mkt_ch"),
            Map.entry("issuingRegion", "iss_mkt_ch"),
            Map.entry("acquiringInstitution", "acq_ins_ch"),
            Map.entry("region", "region_name"),
            Map.entry("channel", "accept_channel"),
            Map.entry("merchantType", "merchant_type"),
            Map.entry("paymentMethod", "payment_method"));

    private final SmartBiProperties properties;

    public SmartBiQueryBuilder(SmartBiProperties properties) {
        this.properties = properties;
    }

    public QueryRequest build(QueryPlan plan) {
        if (plan == null) {
            throw new IllegalArgumentException("QueryPlan 不能为空");
        }
        if (plan.isClarification() || !plan.needsDataQuery()) {
            throw new IllegalArgumentException("CLARIFICATION 或无需取数的 QueryPlan 不能生成 SmartBI JSON");
        }
        String metricField = METRIC_FIELDS.get(plan.metricCode());
        if (metricField == null) {
            throw new IllegalArgumentException("不支持的指标代码：" + plan.metricCode());
        }

        LinkedHashSet<String> rowFields = new LinkedHashSet<>();
        for (String dimension : plan.dimensionCodes()) {
            rowFields.add(requiredField(DIMENSION_FIELDS, dimension, "维度"));
        }
        if (plan.intent() == IntentType.COMPARE_QUERY) {
            plan.comparisonSubjects().stream()
                    .flatMap(subject -> subject.filters().stream())
                    .map(FilterCondition::field)
                    .map(this::comparisonRowField)
                    .filter(value -> !value.isBlank())
                    .forEach(rowFields::add);
        }

        AtomicInteger sequence = new AtomicInteger();
        List<Filter> filters = new ArrayList<>();
        List<RelationNode> rootChildren = new ArrayList<>();
        for (FilterCondition condition : plan.filters()) {
            Filter filter = toFilter(condition, sequence.incrementAndGet());
            filters.add(filter);
            rootChildren.add(RelationNode.leaf(filter));
        }
        if (plan.intent() == IntentType.COMPARE_QUERY) {
            List<RelationNode> subjectNodes = new ArrayList<>();
            for (ComparisonSubject subject : plan.comparisonSubjects()) {
                List<RelationNode> subjectFilters = new ArrayList<>();
                for (FilterCondition condition : subject.filters()) {
                    Filter filter = toFilter(condition, sequence.incrementAndGet());
                    filters.add(filter);
                    subjectFilters.add(RelationNode.leaf(filter));
                }
                subjectNodes.add(subjectFilters.size() == 1
                        ? subjectFilters.get(0)
                        : RelationNode.group("AND", subjectFilters));
            }
            rootChildren.add(RelationNode.group("OR", subjectNodes));
        }

        RelationNode relationNode = rootChildren.isEmpty()
                ? null
                : RelationNode.group("AND", rootChildren);
        return new QueryRequest(
                properties.datasetId(),
                List.copyOf(rowFields),
                List.of(metricField),
                List.copyOf(filters),
                relationNode);
    }

    public String metricField(String metricCode) {
        return requiredField(METRIC_FIELDS, metricCode, "指标");
    }

    public String dimensionField(String dimensionCode) {
        return requiredField(DIMENSION_FIELDS, dimensionCode, "维度");
    }

    private Filter toFilter(FilterCondition condition, int id) {
        String field = requiredField(FILTER_FIELDS, condition.field(), "过滤字段");
        return new Filter(
                String.valueOf(id),
                field,
                condition.operator().toUpperCase(),
                condition.values());
    }

    private String comparisonRowField(String filterCode) {
        if ("tradeDate".equals(filterCode)) {
            return DIMENSION_FIELDS.get("tradeMonth");
        }
        return DIMENSION_FIELDS.getOrDefault(filterCode, "");
    }

    private String requiredField(Map<String, String> fields, String code, String type) {
        String field = fields.get(code);
        if (field == null) {
            throw new IllegalArgumentException("不支持的" + type + "代码：" + code);
        }
        return field;
    }
}
