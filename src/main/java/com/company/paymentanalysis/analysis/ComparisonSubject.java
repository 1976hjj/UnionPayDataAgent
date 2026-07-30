package com.company.paymentanalysis.analysis;

import java.io.Serializable;
import java.util.List;

public record ComparisonSubject(
        String label,
        List<FilterCondition> filters) implements Serializable {

    public ComparisonSubject {
        filters = filters == null ? List.of() : List.copyOf(filters);
    }
}
