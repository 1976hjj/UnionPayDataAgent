package com.company.paymentanalysis.analysis;

import com.company.paymentanalysis.controller.ChatQueryController.QueryContext;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;

public record AnalysisContext(
        LocalDate currentDate,
        String metricCode,
        List<String> dimensionCodes,
        List<FilterCondition> filters) implements Serializable {

    public AnalysisContext {
        currentDate = currentDate == null ? LocalDate.now() : currentDate;
        metricCode = metricCode == null ? "" : metricCode;
        dimensionCodes = dimensionCodes == null ? List.of() : List.copyOf(dimensionCodes);
        filters = filters == null ? List.of() : List.copyOf(filters);
    }

    public static AnalysisContext from(QueryContext context, LocalDate currentDate) {
        if (context == null) {
            return new AnalysisContext(currentDate, "", List.of(), List.of());
        }
        List<FilterCondition> periodFilters = context.hasPeriod()
                ? List.of(new FilterCondition(
                        "tradeDate", "BETWEEN", List.of(context.startDate(), context.endDate())))
                : List.of();
        String metric = context.metricIds() == null || context.metricIds().isEmpty()
                ? ""
                : context.metricIds().get(0);
        return new AnalysisContext(currentDate, metric, context.dimensionIds(), periodFilters);
    }
}
