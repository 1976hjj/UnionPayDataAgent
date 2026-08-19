package com.company.paymentanalysis.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the application-level, machine-readable process audit trail. */
@ConfigurationProperties(prefix = "process-audit")
public record ProcessAuditProperties(
        boolean enabled,
        String file,
        int maxTextLength) {

    public ProcessAuditProperties {
        file = file == null || file.isBlank() ? "logs/process-audit.jsonl" : file.trim();
        maxTextLength = maxTextLength <= 0 ? 200_000 : maxTextLength;
    }
}
