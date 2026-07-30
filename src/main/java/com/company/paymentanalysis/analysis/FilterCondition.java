package com.company.paymentanalysis.analysis;

import java.io.Serializable;
import java.util.List;

public record FilterCondition(
        String field,
        String operator,
        List<String> values) implements Serializable {

    public FilterCondition {
        values = values == null ? List.of() : List.copyOf(values);
    }
}
