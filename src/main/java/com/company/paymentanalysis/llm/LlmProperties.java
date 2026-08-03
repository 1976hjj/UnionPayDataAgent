package com.company.paymentanalysis.llm;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "llm")
public record LlmProperties(
        boolean mockEnabled,
        String baseUrl,
        String apiKey,
        String model,
        List<String> models,
        String chatPath,
        boolean jsonMode,
        boolean thinkingSupported,
        boolean thinkingEnabled,
        int maxTokens,
        double temperature,
        int maxAttempts,
        long retryDelayMs) {
}
