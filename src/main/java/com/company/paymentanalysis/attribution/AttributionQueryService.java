package com.company.paymentanalysis.attribution;

import com.company.paymentanalysis.attribution.AttributionModels.DimensionFilter;
import com.company.paymentanalysis.attribution.AttributionModels.EffectiveRequest;
import com.company.paymentanalysis.query.QueryMetadataCatalog;
import com.company.paymentanalysis.smartbi.SmartBiClient;
import com.company.paymentanalysis.smartbi.SmartBiModels.Filter;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryRequest;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryResponse;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryTrace;
import com.company.paymentanalysis.smartbi.SmartBiModels.RelationNode;
import com.company.paymentanalysis.smartbi.SmartBiProperties;
import java.io.Serializable;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AttributionQueryService {

    private static final String PERIOD_FIELD = "sett_dt_Month2";

    private final SmartBiClient smartBiClient;
    private final SmartBiProperties properties;

    public AttributionQueryService(SmartBiClient smartBiClient, SmartBiProperties properties) {
        this.smartBiClient = smartBiClient;
        this.properties = properties;
    }

    public QueryExecution queryOverall(EffectiveRequest request) {
        return execute("overall", null, build(request, null, List.of()));
    }

    public QueryExecution queryDimension(
            EffectiveRequest request, String dimensionId, List<DimensionFilter> pathFilters, int depth) {
        if (!AttributionCatalog.isDimension(dimensionId)) {
            throw new IllegalArgumentException("归因查询包含非法维度：" + dimensionId);
        }
        return execute("depth" + depth, dimensionId, build(request, dimensionId, pathFilters));
    }

    private QueryExecution execute(String stage, String dimensionId, QueryRequest query) {
        return new QueryExecution(smartBiClient.query(query), new QueryTrace(stage, dimensionId, query));
    }

    private QueryRequest build(
            EffectiveRequest request, String dimensionId, List<DimensionFilter> pathFilters) {
        YearMonth current = YearMonth.parse(request.currentPeriod());
        YearMonth comparison = YearMonth.parse(request.comparisonPeriod());
        List<String> columns = new ArrayList<>();
        columns.add(request.metricId());
        AttributionCatalog.comparisonMetric(request.metricId(), current, comparison).ifPresent(columns::add);

        Filter currentFilter = new Filter("period-current", PERIOD_FIELD, "EQUALS", List.of(current.toString()));
        Filter comparisonFilter =
                new Filter("period-comparison", PERIOD_FIELD, "EQUALS", List.of(comparison.toString()));
        List<Filter> filters = new ArrayList<>(List.of(currentFilter, comparisonFilter));
        List<RelationNode> conditions = new ArrayList<>();
        conditions.add(RelationNode.group(
                "OR", List.of(RelationNode.leaf(currentFilter), RelationNode.leaf(comparisonFilter))));

        int index = 1;
        for (DimensionFilter filter : concat(request.dimensionFilters(), pathFilters)) {
            Filter smartBiFilter = new Filter(
                    "dimension-" + index++,
                    QueryMetadataCatalog.smartBiFilterField(filter.dimensionId()),
                    filter.operator(),
                    filter.values());
            filters.add(smartBiFilter);
            conditions.add(RelationNode.leaf(smartBiFilter));
        }

        List<String> rows = new ArrayList<>(List.of(PERIOD_FIELD));
        if (dimensionId != null) {
            rows.add(QueryMetadataCatalog.smartBiField(dimensionId));
        }
        return new QueryRequest(
                properties.datasetId(),
                rows,
                List.copyOf(columns),
                List.copyOf(filters),
                RelationNode.group("AND", conditions));
    }

    private List<DimensionFilter> concat(
            List<DimensionFilter> requestFilters, List<DimensionFilter> pathFilters) {
        List<DimensionFilter> result = new ArrayList<>(requestFilters);
        result.addAll(pathFilters);
        return result;
    }

    public record QueryExecution(QueryResponse response, QueryTrace trace) implements Serializable {
    }
}
