package com.company.paymentanalysis.ragflow;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings shared by the workbook-backed mock and the future RAGFlow client. */
@ConfigurationProperties(prefix = "ragflow")
public record RagflowProperties(
        boolean enabled,
        boolean mockEnabled,
        String baseUrl,
        String retrievalPath,
        String apiKey,
        List<String> datasetIds,
        DocumentIds documentIds,
        double similarityThreshold,
        double vectorSimilarityWeight,
        String rerankId,
        int topK,
        int page,
        int pageSize,
        MockFiles mockFiles) {

    public record DocumentIds(String metrics, String dimensions, String values) {
    }

    public record MockFiles(String metrics, String dimensions, String values) {
    }
}
