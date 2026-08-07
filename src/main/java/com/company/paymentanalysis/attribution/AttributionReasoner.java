package com.company.paymentanalysis.attribution;

import com.company.paymentanalysis.attribution.AttributionCatalog.AttributionDimension;
import com.company.paymentanalysis.attribution.AttributionModels.AttributionReport;
import com.company.paymentanalysis.attribution.AttributionModels.AnalysisBranch;
import com.company.paymentanalysis.attribution.AttributionModels.BranchAction;
import com.company.paymentanalysis.attribution.AttributionModels.EffectiveRequest;
import com.company.paymentanalysis.attribution.AttributionModels.Evidence;
import com.company.paymentanalysis.attribution.AttributionModels.OverallEvidence;
import com.company.paymentanalysis.attribution.AttributionModels.PathNode;
import com.company.paymentanalysis.attribution.AttributionModels.StopInfo;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient.LlmResultMessage;
import java.io.Serializable;
import java.util.List;

public interface AttributionReasoner {

    PlanDecision plan(EffectiveRequest request, List<AttributionDimension> candidates, int maxDimensions);

    ReflectionDecision reflect(
            EffectiveRequest request,
            OverallEvidence overall,
            List<Evidence> currentEvidence,
            List<AnalysisBranch> branches,
            List<AttributionDimension> remainingDimensions,
            int remainingQueryBudget,
            int maxActions);

    ReportDecision report(
            EffectiveRequest request,
            OverallEvidence overall,
            List<Evidence> evidence,
            List<PathNode> primaryPath,
            List<AnalysisBranch> branches,
            StopInfo stop);

    String engineLabel(String requestedModel);

    record PlanDecision(
            String hypothesis,
            List<String> dimensions,
            String reason,
            LlmResultMessage llmMessage) implements Serializable {
    }

    record ReflectionDecision(
            String reflection,
            List<BranchAction> actions,
            LlmResultMessage llmMessage) implements Serializable {

        public ReflectionDecision {
            actions = actions == null ? List.of() : List.copyOf(actions);
        }
    }

    record ReportDecision(AttributionReport report, LlmResultMessage llmMessage) implements Serializable {
    }
}
