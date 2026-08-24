package com.company.paymentanalysis.ragflow;

import java.util.List;

/** Returns small, typed metadata candidate sets rather than one mixed global TopN. */
public interface MetadataRetrievalTool {

    RetrievedMetadata retrieveForQuery(String userMessage, String semanticIntent);

    RetrievedMetadata retrieveForAttribution(String userMessage, String semanticIntent);

    static MetadataRetrievalTool noOp() {
        return new MetadataRetrievalTool() {
            @Override
            public RetrievedMetadata retrieveForQuery(String userMessage, String semanticIntent) {
                return RetrievedMetadata.empty();
            }

            @Override
            public RetrievedMetadata retrieveForAttribution(String userMessage, String semanticIntent) {
                return RetrievedMetadata.empty();
            }
        };
    }

    record MetadataCandidate(
            Scope scope, String fieldId, String fieldName, String value,
            String description, double score, String queryTerm, String source) {

        public MetadataCandidate {
            queryTerm = queryTerm == null ? "" : queryTerm;
        }

        public MetadataCandidate(
                Scope scope, String fieldId, String fieldName, String value,
                String description, double score, String source) {
            this(scope, fieldId, fieldName, value, description, score, "", source);
        }
    }

    record RetrievedMetadata(List<MetadataCandidate> metrics,
                             List<MetadataCandidate> dimensions,
                             List<MetadataCandidate> values,
                             boolean fallback) {
        public RetrievedMetadata {
            metrics = metrics == null ? List.of() : List.copyOf(metrics);
            dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
            values = values == null ? List.of() : List.copyOf(values);
        }

        public static RetrievedMetadata empty() {
            return new RetrievedMetadata(List.of(), List.of(), List.of(), true);
        }

        public boolean isEmpty() {
            return metrics.isEmpty() && dimensions.isEmpty() && values.isEmpty();
        }
    }

    enum Scope { METRIC, DIMENSION, VALUE }
}
