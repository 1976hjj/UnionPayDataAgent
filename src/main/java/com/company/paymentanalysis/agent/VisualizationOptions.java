package com.company.paymentanalysis.agent;

import com.company.paymentanalysis.artifact.model.ChartArtifactPayload.ChartType;
import java.io.Serializable;
import java.util.List;

/** Explicit inputs used by the visualization skill and, later, the planner. */
public record VisualizationOptions(
        String sourceArtifactId,
        ChartType chartType,
        String dimensionId,
        List<String> metricIds,
        String title) implements Serializable {

    public VisualizationOptions {
        chartType = chartType == null ? ChartType.AUTO : chartType;
        metricIds = metricIds == null ? List.of() : List.copyOf(metricIds);
    }
}
