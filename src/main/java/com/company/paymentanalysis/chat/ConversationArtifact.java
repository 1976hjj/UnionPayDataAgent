package com.company.paymentanalysis.chat;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * A compact, immutable reference to an analysis result. The full SmartBI trace
 * stays in the workflow response; conversation memory keeps only the contract
 * and the evidence needed for a grounded follow-up.
 */
public record ConversationArtifact(
        String id,
        String type,
        String title,
        String createdAt,
        /** Legacy field kept so existing Redis JSON remains readable. */
        String summary,
        String requestContract,
        /** Legacy mixed field; it is never treated as verified facts. */
        String evidence,
        Map<String, String> attributes,
        List<VerifiedFact> verifiedFacts,
        String modelNarrative) implements Serializable {

    public ConversationArtifact {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        verifiedFacts = verifiedFacts == null ? List.of() : List.copyOf(verifiedFacts);
        modelNarrative = modelNarrative == null || modelNarrative.isBlank() ? summary : modelNarrative;
    }

    /** Backward-compatible constructor used by old tests and callers. */
    public ConversationArtifact(
            String id, String type, String title, String createdAt,
            String summary, String requestContract, String evidence) {
        this(id, type, title, createdAt, summary, requestContract, evidence,
                Map.of(), List.of(), summary);
    }

    public record VerifiedFact(
            String code, String label, String value, String source) implements Serializable {
    }
}
