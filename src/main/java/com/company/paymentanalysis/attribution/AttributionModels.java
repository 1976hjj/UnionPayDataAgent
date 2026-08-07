package com.company.paymentanalysis.attribution;

import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient.LlmResultMessage;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryTrace;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

public final class AttributionModels {

    private AttributionModels() {
    }

    public record AttributionRequest(
            String metricId,
            String currentPeriod,
            String comparisonPeriod,
            List<DimensionFilter> dimensionFilters,
            Integer maxDepth,
            Integer maxQueries,
            Integer topN,
            Integer maxBranches,
            String model) implements Serializable {

        public AttributionRequest {
            dimensionFilters = dimensionFilters == null ? List.of() : List.copyOf(dimensionFilters);
        }
    }

    public record EffectiveRequest(
            String metricId,
            String currentPeriod,
            String comparisonPeriod,
            List<DimensionFilter> dimensionFilters,
            int maxDepth,
            int maxQueries,
            int topN,
            int maxBranches,
            String model) implements Serializable {
    }

    public record DimensionFilter(
            String dimensionId, String operator, List<String> values) implements Serializable {

        public DimensionFilter {
            values = values == null ? List.of() : List.copyOf(values);
        }
    }

    public record OverallEvidence(
            BigDecimal currentValue,
            BigDecimal comparisonValue,
            BigDecimal changeAmount,
            BigDecimal changeRate,
            BigDecimal smartBiComparisonRate,
            String direction) implements Serializable {
    }

    public record MemberEvidence(
            int rank,
            String memberValue,
            BigDecimal currentValue,
            BigDecimal comparisonValue,
            BigDecimal changeAmount,
            BigDecimal changeRate,
            BigDecimal contributionRate,
            String direction,
            boolean alignedWithOverall) implements Serializable {
    }

    public record Evidence(
            String id,
            String branchId,
            int depth,
            String hypothesis,
            String dimensionId,
            String dimensionName,
            List<DimensionFilter> pathFilters,
            List<MemberEvidence> members,
            MemberEvidence primaryDriver,
            BigDecimal topNCoverageRate,
            boolean dataConsistent) implements Serializable {
    }

    public record PathNode(
            int depth,
            String dimensionId,
            String dimensionName,
            String memberValue,
            BigDecimal changeAmount,
            BigDecimal contributionRate) implements Serializable {
    }

    public record AnalysisBranch(
            String id,
            String parentBranchId,
            String role,
            String status,
            int depth,
            List<DimensionFilter> pathFilters,
            List<PathNode> path,
            String hypothesis,
            String stopReason,
            int queryCount) implements Serializable {

        public AnalysisBranch {
            pathFilters = pathFilters == null ? List.of() : List.copyOf(pathFilters);
            path = path == null ? List.of() : List.copyOf(path);
        }
    }

    public record BranchAction(
            String action,
            String role,
            String selectedEvidenceId,
            String selectedMember,
            String nextDimension,
            String priority,
            String hypothesis,
            String reason) implements Serializable {
    }

    public record ReasoningStep(
            int depth,
            String phase,
            String hypothesis,
            List<String> proposedDimensions,
            String selectedEvidenceId,
            String selectedMember,
            String nextDimension,
            String reason,
            List<BranchAction> branchActions,
            LlmResultMessage llmMessage) implements Serializable {

        public ReasoningStep {
            proposedDimensions = proposedDimensions == null ? List.of() : List.copyOf(proposedDimensions);
            branchActions = branchActions == null ? List.of() : List.copyOf(branchActions);
        }
    }

    public record StopInfo(String code, String detail) implements Serializable {
    }

    public record AttributionReport(
            String summary, List<String> findings, List<String> recommendations) implements Serializable {
    }

    public record WorkflowStep(
            String node, String name, String status, String detail) implements Serializable {
    }

    public record AttributionResponse(
            String status,
            String metricId,
            String metricName,
            String currentPeriod,
            String comparisonPeriod,
            OverallEvidence overall,
            List<Evidence> evidence,
            List<PathNode> primaryPath,
            List<AnalysisBranch> branches,
            List<ReasoningStep> reasoning,
            StopInfo stop,
            AttributionReport report,
            int queryCount,
            String executionEngine,
            List<WorkflowStep> workflowSteps,
            List<QueryTrace> smartBiQueries) implements Serializable {
    }
}
