package com.company.paymentanalysis.semantic;

import java.util.List;

/** Stable query-flow boundary implemented by the already-wired remote/Mock RAGFlow client. */
public interface BusinessSemanticRetrievalTool {

    RetrievalResult retrieve(String originalMessage, List<String> searchTerms);

    static BusinessSemanticRetrievalTool noOp() {
        return (message, terms) -> RetrievalResult.empty();
    }

    record RetrievalResult(List<RuleCandidate> candidates, boolean mocked) {
        public RetrievalResult {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        public static RetrievalResult empty() {
            return new RetrievalResult(List.of(), false);
        }
    }

    record RuleCandidate(
            BusinessSemanticRule rule, List<String> matchedTerms, double bestScore) {
        public RuleCandidate {
            matchedTerms = matchedTerms == null ? List.of() : List.copyOf(matchedTerms);
        }
    }
}
