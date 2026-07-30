package com.company.paymentanalysis.calculation;

import java.io.Serializable;
import java.util.List;

public record RankingResult(
        List<RankingItem> items,
        int requestedLimit,
        int actualCount,
        SortDirection direction,
        List<String> warnings) implements Serializable {

    public RankingResult {
        items = items == null ? List.of() : List.copyOf(items);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
