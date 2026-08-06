package com.company.paymentanalysis.smartbi;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "smartbi")
public record SmartBiProperties(
        String datasetId,
        boolean mockEnabled,
        String mockBaseUrl) {
}
