package com.company.paymentanalysis.ragflow;

import com.company.paymentanalysis.ragflow.MetadataRetrievalTool.MetadataCandidate;
import com.company.paymentanalysis.ragflow.MetadataRetrievalTool.RetrievedMetadata;

/** Formats retrieval output as bounded mapping evidence, never as raw chunks. */
public final class RetrievedMetadataPrompt {

    private RetrievedMetadataPrompt() {
    }

    public static String render(RetrievedMetadata metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "No metadata candidates were retrieved.";
        }
        StringBuilder result = new StringBuilder();
        append(result, "metricCandidates", metadata.metrics());
        append(result, "dimensionCandidates", metadata.dimensions());
        append(result, "filterValueCandidates", metadata.values());
        result.append("Only select field IDs present in these candidates. A value candidate supplies a filter value and its owning dimensionId.");
        return result.toString();
    }

    private static void append(StringBuilder result, String label, Iterable<MetadataCandidate> candidates) {
        result.append(label).append(":\n");
        for (MetadataCandidate candidate : candidates) {
            result.append("- fieldId=").append(candidate.fieldId())
                    .append("; name=").append(candidate.fieldName())
                    .append("; value=").append(candidate.value())
                    .append("; description=").append(candidate.description())
                    .append("; score=").append(String.format(java.util.Locale.ROOT, "%.2f", candidate.score()))
                    .append('\n');
        }
    }
}
