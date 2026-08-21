package com.company.paymentanalysis.permission;

import com.company.paymentanalysis.query.QueryMetadataCatalog;
import com.company.paymentanalysis.smartbi.SmartBiModels.Filter;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryRequest;
import com.company.paymentanalysis.smartbi.SmartBiModels.RelationNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Adds mandatory permission leaves outside the business query's relation tree. */
@Component
public class PermissionQueryEnricher {

    public QueryRequest apply(QueryRequest source, PermissionScope scope) {
        List<Filter> filters = new ArrayList<>(source.filters());
        List<RelationNode> outerConditions = new ArrayList<>();
        RelationNode businessCondition = businessCondition(source);
        if (businessCondition != null) {
            outerConditions.add(businessCondition);
        }

        Set<String> usedIds = new HashSet<>();
        source.filters().stream().map(Filter::id).forEach(usedIds::add);
        scope.valuesByDimension().forEach((dimensionId, values) -> {
            String id = uniqueId("permission-" + dimensionId, usedIds);
            String operation = values.size() == 1 ? "EQUALS" : "IN";
            Filter permissionFilter = new Filter(
                    id,
                    QueryMetadataCatalog.smartBiFilterField(dimensionId),
                    operation,
                    values);
            filters.add(permissionFilter);
            outerConditions.add(RelationNode.leaf(permissionFilter));
        });

        return new QueryRequest(
                source.dataSetId(), source.rows(), source.columns(), List.copyOf(filters),
                RelationNode.group("AND", List.copyOf(outerConditions)),
                source.orderBys(), source.rowsPerPage());
    }

    private RelationNode businessCondition(QueryRequest source) {
        if (source.relationNode() != null) {
            return source.relationNode();
        }
        if (source.filters().isEmpty()) {
            return null;
        }
        return RelationNode.group("AND", source.filters().stream().map(RelationNode::leaf).toList());
    }

    private String uniqueId(String candidate, Set<String> usedIds) {
        String id = candidate;
        int suffix = 2;
        while (!usedIds.add(id)) {
            id = candidate + "-" + suffix++;
        }
        return id;
    }
}
