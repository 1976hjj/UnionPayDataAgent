package com.company.paymentanalysis.attribution;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deterministic guardrails for the attribution scheduler, independent of business dimensions. */
@ConfigurationProperties(prefix = "attribution.policy")
public record AttributionPolicyProperties(
        int maxInitialDimensions,
        int defaultMaxBranches,
        int hardMaxBranches,
        int reservedQueries,
        BigDecimal minAlignedContributionRate,
        int minimumDistinctMembers) {

    public AttributionPolicyProperties {
        maxInitialDimensions = maxInitialDimensions <= 0 ? 3 : maxInitialDimensions;
        defaultMaxBranches = defaultMaxBranches <= 0 ? 2 : defaultMaxBranches;
        hardMaxBranches = hardMaxBranches <= 0 ? 3 : hardMaxBranches;
        reservedQueries = Math.max(0, reservedQueries);
        minAlignedContributionRate = minAlignedContributionRate == null
                ? new BigDecimal("10")
                : minAlignedContributionRate;
        minimumDistinctMembers = minimumDistinctMembers <= 0 ? 2 : minimumDistinctMembers;
    }
}
