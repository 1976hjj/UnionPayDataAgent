package com.company.paymentanalysis.attribution;

import com.company.paymentanalysis.attribution.AttributionModels.DimensionFilter;
import com.company.paymentanalysis.attribution.AttributionModels.EffectiveRequest;
import com.company.paymentanalysis.query.QueryMetadataCatalog;
import com.company.paymentanalysis.smartbi.AuthorizedSmartBiClient;
import com.company.paymentanalysis.smartbi.AuthorizedSmartBiClient.PreparedQuery;
import com.company.paymentanalysis.smartbi.SmartBiModels.Filter;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryRequest;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryResponse;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryTrace;
import com.company.paymentanalysis.smartbi.SmartBiModels.RelationNode;
import com.company.paymentanalysis.smartbi.SmartBiProperties;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

@Service
public class AttributionQueryService {

    private static final String PERIOD_FIELD = "sett_dt_Month2";

    private final AuthorizedSmartBiClient smartBiClient;
    private final SmartBiProperties properties;
    private final AtomicLong callSequence = new AtomicLong();

    public AttributionQueryService(AuthorizedSmartBiClient smartBiClient, SmartBiProperties properties) {
        this.smartBiClient = smartBiClient;
        this.properties = properties;
    }

    public QueryExecution queryOverall(EffectiveRequest request) {
        return queryOverall(request, ignored -> { });
    }

    public QueryExecution queryOverall(EffectiveRequest request, Consumer<SmartBiCall> observer) {
        return executePeriods("overall", null, request, List.of(), observer);
    }

    public QueryExecution queryDimension(
            EffectiveRequest request, String dimensionId, List<DimensionFilter> pathFilters, int depth) {
        return queryDimension(request, dimensionId, pathFilters, depth, ignored -> { });
    }

    public QueryExecution queryDimension(
            EffectiveRequest request,
            String dimensionId,
            List<DimensionFilter> pathFilters,
            int depth,
            Consumer<SmartBiCall> observer) {
        if (!AttributionCatalog.isDimension(dimensionId)) {
            throw new IllegalArgumentException("归因查询包含非法维度：" + dimensionId);
        }
        return executePeriods("depth" + depth, dimensionId, request, pathFilters, observer);
    }

    private QueryExecution executePeriods(
            String stage,
            String dimensionId,
            EffectiveRequest request,
            List<DimensionFilter> pathFilters,
            Consumer<SmartBiCall> observer) {
        PreparedQuery currentPrepared = prepare(request, build(
                request, dimensionId, pathFilters, request.currentPeriod()));
        PreparedQuery comparisonPrepared = prepare(request, build(
                request, dimensionId, pathFilters, request.comparisonPeriod()));
        QueryRequest currentQuery = currentPrepared.request();
        QueryRequest comparisonQuery = comparisonPrepared.request();
        QueryResponse current = executeCall(
                stage + "-current", dimensionId, request.currentPeriod(), currentPrepared, observer);
        QueryResponse comparison = executeCall(
                stage + "-comparison", dimensionId, request.comparisonPeriod(), comparisonPrepared, observer);

        List<Map<String, Object>> rows = new ArrayList<>(current.data());
        rows.addAll(comparison.data());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "two-period SmartBI queries");
        metadata.put("current", current.metadata());
        metadata.put("comparison", comparison.metadata());
        QueryResponse merged = new QueryResponse(
                current.requestId() + "+" + comparison.requestId(), List.copyOf(rows), Map.copyOf(metadata));
        return new QueryExecution(
                merged,
                List.of(
                        new QueryTrace(stage + "-current", dimensionId, currentQuery),
                        new QueryTrace(stage + "-comparison", dimensionId, comparisonQuery)));
    }

    private QueryResponse executeCall(
            String stage,
            String dimensionId,
            String period,
            PreparedQuery preparedQuery,
            Consumer<SmartBiCall> observer) {
        QueryRequest query = preparedQuery.request();
        String callId = "smartbi-call-" + callSequence.incrementAndGet();
        observer.accept(new SmartBiCall(callId, stage, dimensionId, period, "RUNNING", query, null, null));
        try {
            QueryResponse response = smartBiClient.query(preparedQuery);
            observer.accept(new SmartBiCall(callId, stage, dimensionId, period, "COMPLETED", query, response, null));
            return response;
        } catch (RuntimeException exception) {
            observer.accept(new SmartBiCall(
                    callId, stage, dimensionId, period, "FAILED", query, null, rootMessage(exception)));
            throw exception;
        }
    }

    private PreparedQuery prepare(EffectiveRequest request, QueryRequest query) {
        return request.permissionScope() == null
                ? smartBiClient.prepare(request.loginUsername(), query)
                : smartBiClient.prepare(request.permissionScope(), query);
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private QueryRequest build(
            EffectiveRequest request,
            String dimensionId,
            List<DimensionFilter> pathFilters,
            String period) {
        List<String> columns = List.of(request.metricId());

        Filter periodFilter = new Filter("1", PERIOD_FIELD, "EQUALS", List.of(period));
        List<Filter> filters = new ArrayList<>(List.of(periodFilter));
        List<RelationNode> conditions = new ArrayList<>(List.of(RelationNode.leaf(periodFilter)));

        int index = 2;
        for (DimensionFilter filter : concat(request.dimensionFilters(), pathFilters)) {
            Filter smartBiFilter = new Filter(
                    String.valueOf(index++),
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

    public record QueryExecution(QueryResponse response, List<QueryTrace> traces) implements Serializable {

        public QueryExecution(QueryResponse response, QueryTrace trace) {
            this(response, List.of(trace));
        }
    }

    public record SmartBiCall(
            String callId,
            String stage,
            String dimensionCode,
            String period,
            String status,
            QueryRequest request,
            QueryResponse response,
            String error) implements Serializable {
    }
}
