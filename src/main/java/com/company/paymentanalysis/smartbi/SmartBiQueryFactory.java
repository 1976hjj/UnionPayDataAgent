package com.company.paymentanalysis.smartbi;

import com.company.paymentanalysis.controller.AttributionController.AttributionRequest;
import com.company.paymentanalysis.smartbi.SmartBiModels.Filter;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryRequest;
import com.company.paymentanalysis.smartbi.SmartBiModels.RelationNode;
import com.company.paymentanalysis.smartbi.SmartBiQueryTranslator.TranslatedQueryPlan;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class SmartBiQueryFactory {

    public QueryRequest create(
            AttributionRequest request,
            TranslatedQueryPlan plan,
            String dimensionCode,
            String driverName) {
        YearMonth current = YearMonth.parse(request.currentPeriod());
        YearMonth comparison = "yearOnYear".equals(request.comparisonType())
                ? current.minusYears(1)
                : current.minusMonths(1);

        Filter currentFilter = new Filter("1", plan.periodField(), "EQUALS", List.of(current.toString()));
        Filter comparisonFilter = new Filter("2", plan.periodField(), "EQUALS", List.of(comparison.toString()));
        List<Filter> filters = new ArrayList<>(List.of(currentFilter, comparisonFilter));
        List<RelationNode> andChildren = new ArrayList<>();
        andChildren.add(RelationNode.group(
                "OR",
                List.of(RelationNode.leaf(currentFilter), RelationNode.leaf(comparisonFilter))));

        if (StringUtils.hasText(driverName)) {
            Filter driverFilter = new Filter(
                    "3",
                    plan.dimensionFields().get(request.level1DimensionCode()),
                    "EQUALS",
                    List.of(driverName));
            filters.add(driverFilter);
            andChildren.add(RelationNode.leaf(driverFilter));
        }
        if (StringUtils.hasText(request.businessScope())) {
            Filter scopeFilter = new Filter(
                    "4",
                    plan.businessScopeField(),
                    "EQUALS",
                    List.of(request.businessScope()));
            filters.add(scopeFilter);
            andChildren.add(RelationNode.leaf(scopeFilter));
        }

        List<String> rows = new ArrayList<>(List.of(plan.periodField()));
        if (StringUtils.hasText(dimensionCode)) {
            rows.add(plan.dimensionFields().get(dimensionCode));
        }
        return new QueryRequest(
                plan.dataSetId(),
                rows,
                List.of(plan.metricField(), plan.comparisonMetricField()),
                filters,
                RelationNode.group("AND", andChildren));
    }
}
