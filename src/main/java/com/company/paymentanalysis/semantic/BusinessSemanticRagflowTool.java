package com.company.paymentanalysis.semantic;

import com.company.paymentanalysis.ragflow.RagflowProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * Production-ready fourth-document retrieval. It shares the exact RAGFlow
 * connection and mode switch used by metric, dimension and value retrieval.
 */
@Primary
@Component
public class BusinessSemanticRagflowTool implements BusinessSemanticRetrievalTool {

    private static final Logger LOGGER = LoggerFactory.getLogger(BusinessSemanticRagflowTool.class);
    private static final int RESULT_LIMIT = 5;

    private final RagflowProperties ragflow;
    private final BusinessSemanticProperties semantics;
    private final MockBusinessSemanticRetrievalTool localFallback;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public BusinessSemanticRagflowTool(
            RagflowProperties ragflow,
            BusinessSemanticProperties semantics,
            MockBusinessSemanticRetrievalTool localFallback,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper) {
        this.ragflow = ragflow;
        this.semantics = semantics;
        this.localFallback = localFallback;
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    @Override
    public RetrievalResult retrieve(String originalMessage, List<String> searchTerms) {
        if (!semantics.enabled()) return RetrievalResult.empty();
        if (!ragflow.enabled() || ragflow.mockEnabled()) {
            return localFallback.retrieveLocal(originalMessage, searchTerms);
        }
        try {
            return retrieveRemote(originalMessage, searchTerms);
        } catch (RuntimeException exception) {
            LOGGER.warn("Business semantic retrieval failed; using local JSONL fallback: {}",
                    exception.getMessage());
            return localFallback.retrieveLocal(originalMessage, searchTerms);
        }
    }

    private RetrievalResult retrieveRemote(String originalMessage, List<String> searchTerms) {
        validateRemoteConfiguration();
        Map<String, CandidateAccumulator> merged = new LinkedHashMap<>();
        for (String query : queries(originalMessage, searchTerms)) {
            for (RuleCandidate candidate : retrieveOne(query)) {
                merged.merge(candidate.rule().knowledgeId(),
                        new CandidateAccumulator(candidate.rule(), candidate.matchedTerms(), candidate.bestScore()),
                        CandidateAccumulator::merge);
            }
        }
        List<RuleCandidate> candidates = merged.values().stream()
                .map(CandidateAccumulator::candidate)
                .sorted(Comparator.comparingDouble(RuleCandidate::bestScore).reversed()
                        .thenComparing(candidate -> candidate.rule().knowledgeId()))
                .limit(RESULT_LIMIT)
                .toList();
        return new RetrievalResult(candidates, false);
    }

    private List<RuleCandidate> retrieveOne(String question) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("similarity_threshold", semantics.similarityThreshold());
        body.put("vector_similarity_weight", ragflow.vectorSimilarityWeight());
        body.put("use_kg", false);
        body.put("question", question);
        body.put("top_k", ragflow.topK());
        body.put("dataset_ids", ragflow.datasetIds());
        body.put("document_ids", List.of(ragflow.documentIds().businessSemantics()));
        body.put("page", ragflow.page());
        body.put("page_size", RESULT_LIMIT);
        if (StringUtils.hasText(ragflow.rerankId())) body.put("rerank_id", ragflow.rerankId());

        JsonNode response = restClient.post()
                .uri(URI.create(joinUrl(ragflow.baseUrl(), ragflow.retrievalPath())))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ragflow.apiKey())
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        if (response == null || response.path("code").asInt(-1) != 0) {
            throw new IllegalStateException("RAGFlow retrieval returned a non-success response");
        }

        List<RuleCandidate> candidates = new ArrayList<>();
        for (JsonNode chunk : response.path("data").path("chunks")) {
            double score = chunk.path("similarity").asDouble(0);
            if (score < semantics.similarityThreshold()) continue;
            String content = chunk.path("content").asText("");
            if (!StringUtils.hasText(content)) content = chunk.path("content_with_weight").asText("");
            BusinessSemanticRule rule = parseRule(content);
            if (rule == null || !rule.enabled()) continue;
            try {
                BusinessSemanticRuleValidator.validate(rule);
                candidates.add(new RuleCandidate(rule, List.of(question), score));
            } catch (IllegalArgumentException exception) {
                LOGGER.warn("Ignoring unsafe business semantic rule {}: {}",
                        rule.knowledgeId(), exception.getMessage());
            }
        }
        return candidates;
    }

    private BusinessSemanticRule parseRule(String content) {
        if (!StringUtils.hasText(content)) return null;
        String json = content.trim();
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            return objectMapper.readValue(json.substring(start, end + 1), BusinessSemanticRule.class);
        } catch (Exception exception) {
            LOGGER.warn("Ignoring business semantic chunk that is not one complete rule JSON");
            return null;
        }
    }

    private void validateRemoteConfiguration() {
        String documentId = ragflow.documentIds() == null ? "" : ragflow.documentIds().businessSemantics();
        if (!StringUtils.hasText(ragflow.baseUrl()) || !StringUtils.hasText(ragflow.apiKey())
                || ragflow.datasetIds() == null || ragflow.datasetIds().isEmpty()
                || !StringUtils.hasText(documentId)) {
            throw new IllegalStateException("business semantic RAGFlow retrieval is not fully configured");
        }
    }

    private static List<String> queries(String originalMessage, List<String> searchTerms) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (StringUtils.hasText(originalMessage)) result.add(originalMessage.trim());
        if (searchTerms != null) searchTerms.stream().filter(StringUtils::hasText)
                .map(String::trim).forEach(result::add);
        return List.copyOf(result);
    }

    private static String joinUrl(String baseUrl, String path) {
        return baseUrl.replaceAll("/+$", "") + "/" + path.replaceFirst("^/+", "");
    }

    private record CandidateAccumulator(
            BusinessSemanticRule rule, List<String> matchedTerms, double bestScore) {

        CandidateAccumulator merge(CandidateAccumulator other) {
            LinkedHashSet<String> terms = new LinkedHashSet<>(matchedTerms);
            terms.addAll(other.matchedTerms);
            BusinessSemanticRule bestRule = bestScore >= other.bestScore ? rule : other.rule;
            return new CandidateAccumulator(bestRule, List.copyOf(terms), Math.max(bestScore, other.bestScore));
        }

        RuleCandidate candidate() {
            return new RuleCandidate(rule, matchedTerms, bestScore);
        }
    }
}
