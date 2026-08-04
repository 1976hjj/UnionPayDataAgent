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
            String id = String.valueOf(index + 1);
            String field = QueryMetadataCatalog.smartBiFilterField(filter.dimensionId());
            if ("BETWEEN".equals(filter.operator()) && filter.values().size() == 2) {
                filters.add(new Filter(id + "-from", field, "GREATER_EQUALS", List.of(filter.values().get(0))));
                filters.add(new Filter(id + "-to", field, "LESS_EQUALS", List.of(filter.values().get(1))));
            } else {
                filters.add(new Filter(id, field, filter.operator(), filter.values()));
            }
        }
        List<Sort> orderBys = new ArrayList<>();
        for (int index = 0; index < context.sorts().size(); index++) {
            var sort = context.sorts().get(index);
            orderBys.add(new Sort(queryField(sort.fieldId()), sort.direction(), index + 1));
        }
        return new QueryRequest(
                properties.datasetId(),
                rows,
                columns,
                List.copyOf(filters),
                RelationNode.group("AND", filters.stream().map(RelationNode::leaf).toList()),
                List.copyOf(orderBys));
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
