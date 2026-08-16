package com.company.paymentanalysis.attribution;

import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient.LlmResultMessage;
import java.io.Serializable;
import java.util.List;

public final class AttributionTemplateModels {

    private AttributionTemplateModels() {
    }

    public record TemplateChatRequest(
            String userId,
            String conversationId,
            String message,
            List<TemplateConversationMessage> conversationHistory,
            DimensionTemplate currentTemplate,
            String model) implements Serializable {

        public TemplateChatRequest {
            conversationHistory = conversationHistory == null ? List.of() : List.copyOf(conversationHistory);
        }
    }

    public record TemplateConversationMessage(String role, String text) implements Serializable {
    }

    public record DimensionTemplate(
            String name,
            String mode,
            String metricId,
            String metricName,
            String currentPeriod,
            String comparisonPeriod,
            List<TemplateFilter> filters,
            List<DimensionLayer> levels,
            String continuationMode,
            String status,
            String summary) implements Serializable {

        public DimensionTemplate {
            filters = filters == null ? List.of() : List.copyOf(filters);
            levels = levels == null ? List.of() : List.copyOf(levels);
        }

        public static DimensionTemplate auto() {
            return new DimensionTemplate(
                    "自由探索", "AUTO", "", "", "", "", List.of(), List.of(),
                    "AUTO", "DRAFT", "未指定维度时由 Agent 自由探索");
        }
    }

    public record TemplateFilter(
            String dimensionId,
            String dimensionName,
            String userTerm,
            String operator,
            List<String> values,
            String rationale,
            String confidence) implements Serializable {

        public TemplateFilter {
            values = values == null ? List.of() : List.copyOf(values);
        }
    }

    public record DimensionLayer(
            int level,
            List<DimensionSelection> dimensions) implements Serializable {

        public DimensionLayer {
            dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        }
    }

    public record DimensionSelection(
            String dimensionId,
            String dimensionName,
            String userTerm,
            String rationale,
            String mappingHint,
            String confidence) implements Serializable {
    }

    public record MappingIssue(
            String userTerm,
            String reason,
            List<String> candidateDimensionIds) implements Serializable {

        public MappingIssue {
            candidateDimensionIds = candidateDimensionIds == null ? List.of() : List.copyOf(candidateDimensionIds);
        }
    }

    public record TemplateChatResponse(
            String status,
            String reply,
            DimensionTemplate template,
            List<String> unmappedTerms,
            List<MappingIssue> mappingIssues,
            LlmResultMessage intentMessage,
            LlmResultMessage mappingMessage) implements Serializable {

        public TemplateChatResponse {
            unmappedTerms = unmappedTerms == null ? List.of() : List.copyOf(unmappedTerms);
            mappingIssues = mappingIssues == null ? List.of() : List.copyOf(mappingIssues);
        }
    }

    /** Persisted alongside the shared conversation, without verbose LLM traces. */
    public record TemplateConversationState(
            String status,
            DimensionTemplate template,
            List<String> unmappedTerms,
            List<MappingIssue> mappingIssues) implements Serializable {

        public TemplateConversationState {
            unmappedTerms = unmappedTerms == null ? List.of() : List.copyOf(unmappedTerms);
            mappingIssues = mappingIssues == null ? List.of() : List.copyOf(mappingIssues);
        }

        public static TemplateConversationState from(TemplateChatResponse response) {
            return new TemplateConversationState(
                    response.status(), response.template(), response.unmappedTerms(), response.mappingIssues());
        }
    }

    public record TemplateConfirmRequest(
            String userId, String conversationId, DimensionTemplate template) implements Serializable {
    }

    public record TemplateStateRequest(
            String userId, String conversationId, TemplateConversationState state) implements Serializable {
    }
}
