package com.company.paymentanalysis.agent;

import java.io.Serializable;

/** User-selected runtime limits for a confirmed attribution template. */
public record AttributionExecutionOptions(
        Integer maxDepth, Integer maxQueries, Integer topN, Integer maxBranches) implements Serializable {
}
