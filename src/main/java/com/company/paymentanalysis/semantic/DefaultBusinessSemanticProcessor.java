package com.company.paymentanalysis.semantic;

import com.company.paymentanalysis.audit.ProcessAuditLog;
import com.company.paymentanalysis.semantic.BusinessSemanticRetrievalTool.RetrievalResult;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class DefaultBusinessSemanticProcessor implements BusinessSemanticProcessor {

    private final BusinessSemanticRetrievalTool retrievalTool;
    private final BusinessSemanticNormalizer normalizer;
    private final ProcessAuditLog auditLog;

    public DefaultBusinessSemanticProcessor(
            BusinessSemanticRetrievalTool retrievalTool,
            BusinessSemanticNormalizer normalizer,
            ProcessAuditLog auditLog) {
        this.retrievalTool = retrievalTool;
        this.normalizer = normalizer;
        this.auditLog = auditLog;
    }

    @Override
    public NormalizationResult normalize(String originalMessage, QuerySemanticIntent intent) {
        QuerySemanticIntent source = intent == null ? QuerySemanticIntent.empty() : intent;
        List<String> searchTerms = new java.util.ArrayList<>(new LinkedHashSet<>(source.retrievalTerms()));
        auditLog.event("glossary.search.planned", Map.of(
                "originalMessage", originalMessage == null ? "" : originalMessage,
                "searchTerms", searchTerms));
        try {
            RetrievalResult retrieval = retrievalTool.retrieve(originalMessage, searchTerms);
            auditLog.event("glossary.response", Map.of(
                    "mocked", retrieval.mocked(),
                    "candidateCount", retrieval.candidates().size(),
                    "candidates", retrieval.candidates().stream().map(candidate -> Map.of(
                            "knowledgeId", candidate.rule().knowledgeId(),
                            "matchedTerms", candidate.matchedTerms(),
                            "bestScore", candidate.bestScore())).toList()));
            NormalizationResult normalized = normalizer.normalize(source, retrieval);
            auditLog.event("glossary.normalized", Map.of(
                    "appliedRuleIds", normalized.appliedRules().stream()
                            .map(AppliedSemanticRule::knowledgeId).toList(),
                    "before", source,
                    "after", normalized.intent(),
                    "enforcedMetricIds", normalized.enforcedMetricIds(),
                    "enforcedFilters", normalized.enforcedFilters()));
            return normalized;
        } catch (RuntimeException exception) {
            auditLog.event("glossary.fallback", Map.of(
                    "reason", exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage()));
            return NormalizationResult.unchanged(source);
        }
    }
}
