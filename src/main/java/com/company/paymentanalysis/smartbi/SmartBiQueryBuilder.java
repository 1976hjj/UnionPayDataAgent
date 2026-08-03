package com.company.paymentanalysis.smartbi;

import com.company.paymentanalysis.controller.ChatQueryController.QueryContext;
import com.company.paymentanalysis.query.QueryMetadataCatalog;
import com.company.paymentanalysis.smartbi.SmartBiModels.Filter;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryRequest;
import com.company.paymentanalysis.smartbi.SmartBiModels.RelationNode;
import com.company.paymentanalysis.smartbi.SmartBiModels.Sort;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class SmartBiQueryBuilder {

    private final SmartBiProperties properties;

    public SmartBiQueryBuilder(SmartBiProperties properties) {
        this.properties = properties;
    }

    public QueryRequest build(QueryContext context) {
        if (context == null || context.metricIds().isEmpty()) {
            throw new IllegalArgumentException("至少一个度量是生成 SmartBI JSON 的必要条件");
        }
        List<String> rows = context.dimensionIds().stream().map(this::dimensionField).toList();
        List<String> columns = context.metricIds().stream().map(this::metricField).toList();
        List<Filter> filters = new ArrayList<>();
        for (int index = 0; index < context.dimensionFilters().size(); index++) {
            var filter = context.dimensionFilters().get(index);
            filters.add(new Filter(
                    String.valueOf(index + 1),
                    QueryMetadataCatalog.smartBiFilterField(filter.dimensionId()),
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
        if (!QueryMetadataCatalog.isMetric(metricCode)) {
            throw new IllegalArgumentException("不支持的度量代码：" + metricCode);
        }
        return QueryMetadataCatalog.smartBiField(metricCode);
    }

    public String dimensionField(String dimensionCode) {
        if (!QueryMetadataCatalog.isDimension(dimensionCode)) {
            throw new IllegalArgumentException("不支持的维度代码：" + dimensionCode);
        }
        return QueryMetadataCatalog.smartBiField(dimensionCode);
    }

    public String queryField(String code) {
        return QueryMetadataCatalog.smartBiField(code);
    }
}
