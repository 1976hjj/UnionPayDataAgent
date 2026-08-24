package com.company.paymentanalysis.semantic;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Deterministic local mode and outage fallback for fourth-document retrieval. */
@Component
public class MockBusinessSemanticRetrievalTool implements BusinessSemanticRetrievalTool {

    private static final Logger LOGGER = LoggerFactory.getLogger(MockBusinessSemanticRetrievalTool.class);
    private final ObjectMapper objectMapper;
    private final BusinessSemanticProperties properties;
    private final ResourceLoader resourceLoader;
    private volatile List<BusinessSemanticRule> rules;

    public MockBusinessSemanticRetrievalTool(
            ObjectMapper objectMapper,
            BusinessSemanticProperties properties,
            ResourceLoader resourceLoader) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.resourceLoader = resourceLoader;
    }

    @Override
    public RetrievalResult retrieve(String originalMessage, List<String> searchTerms) {
        if (!properties.enabled()) {
            return RetrievalResult.empty();
        }
        return retrieveLocal(originalMessage, searchTerms);
    }

    RetrievalResult retrieveLocal(String originalMessage, List<String> searchTerms) {
        List<String> queries = queries(originalMessage, searchTerms);
        if (queries.isEmpty()) {
            return new RetrievalResult(List.of(), true);
        }
        Map<String, CandidateAccumulator> merged = new ConcurrentHashMap<>();
        queries.parallelStream().forEach(query -> {
            for (BusinessSemanticRule rule : rules()) {
                double score = score(query, rule, originalMessage);
                if (score < properties.similarityThreshold()) continue;
                merged.compute(rule.knowledgeId(), (id, previous) ->
                        previous == null
                                ? new CandidateAccumulator(rule, List.of(query), score)
                                : previous.merge(query, score));
            }
        });
        List<RuleCandidate> candidates = merged.values().stream()
                .map(CandidateAccumulator::candidate)
                .sorted(java.util.Comparator.comparingDouble(RuleCandidate::bestScore).reversed()
                        .thenComparing(candidate -> candidate.rule().knowledgeId()))
                .toList();
        return new RetrievalResult(candidates, true);
    }

    private List<BusinessSemanticRule> rules() {
        if (rules != null) return rules;
        synchronized (this) {
            if (rules == null) rules = loadRules();
            return rules;
        }
    }

    private List<BusinessSemanticRule> loadRules() {
        List<BusinessSemanticRule> loaded = new ArrayList<>();
        try (BufferedReader reader = reader(properties.mockFile())) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (!StringUtils.hasText(line)) continue;
                try {
                    BusinessSemanticRule rule = objectMapper.readValue(line, BusinessSemanticRule.class);
                    BusinessSemanticRuleValidator.validate(rule);
                    if (rule.enabled()) loaded.add(rule);
                } catch (RuntimeException exception) {
                    LOGGER.warn("Ignoring invalid business semantic rule at line {}: {}",
                            lineNumber, exception.getMessage());
                }
            }
        } catch (Exception exception) {
            LOGGER.warn("Business semantic mock file is unavailable; query flow will use existing behavior: {}",
                    exception.getMessage());
            return List.of();
        }
        return List.copyOf(loaded);
    }

    private BufferedReader reader(String location) throws Exception {
        if (!StringUtils.hasText(location)) {
            throw new IllegalArgumentException("business-semantics.mock-file is empty");
        }
        if (location.startsWith("classpath:") || location.startsWith("file:")) {
            Resource resource = resourceLoader.getResource(location);
            return new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8));
        }
        return Files.newBufferedReader(Path.of(location), StandardCharsets.UTF_8);
    }

    private double score(String query, BusinessSemanticRule rule, String originalMessage) {
        if (!contextMatches(rule, originalMessage)) return 0;
        String normalizedQuery = normalize(query);
        double best = 0;
        for (String phrase : rule.matchPhrases()) {
            String normalizedPhrase = normalize(phrase);
            if (normalizedQuery.equals(normalizedPhrase)) best = Math.max(best, 1.0);
            else if (normalizedQuery.contains(normalizedPhrase)) best = Math.max(best, 0.98);
            else if (normalizedQuery.length() >= 2 && normalizedPhrase.contains(normalizedQuery)) {
                best = Math.max(best, 0.90);
            }
        }
        return best;
    }

    private boolean contextMatches(BusinessSemanticRule rule, String originalMessage) {
        BusinessSemanticRule.MatchPolicy policy = rule.matchPolicy();
        if (policy == null || policy.requiredContextTerms().isEmpty()) return true;
        String normalizedMessage = normalize(originalMessage);
        return policy.requiredContextTerms().stream().anyMatch(term -> normalizedMessage.contains(normalize(term)));
    }

    private List<String> queries(String originalMessage, List<String> searchTerms) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (StringUtils.hasText(originalMessage)) result.add(originalMessage.trim());
        if (searchTerms != null) searchTerms.stream().filter(StringUtils::hasText)
                .map(String::trim).forEach(result::add);
        return List.copyOf(result);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s，,。；;：:（）()\\[\\]{}\"']+", "");
    }

    private record CandidateAccumulator(
            BusinessSemanticRule rule, List<String> matchedTerms, double bestScore) {
        CandidateAccumulator merge(String matchedTerm, double score) {
            LinkedHashSet<String> merged = new LinkedHashSet<>(matchedTerms);
            merged.add(matchedTerm);
            return new CandidateAccumulator(rule, List.copyOf(merged), Math.max(bestScore, score));
        }

        RuleCandidate candidate() {
            return new RuleCandidate(rule, matchedTerms, bestScore);
        }
    }
}
