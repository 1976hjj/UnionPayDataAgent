package com.company.paymentanalysis.semantic;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "business-semantics")
public record BusinessSemanticProperties(
        boolean enabled,
        String mockFile,
        double similarityThreshold) {
}
