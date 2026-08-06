package com.company.paymentanalysis.attribution;

import com.company.paymentanalysis.attribution.AttributionCatalog.AttributionDimension;
import com.company.paymentanalysis.attribution.AttributionModels.AttributionReport;
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

    NextDecision next(
            EffectiveRequest request,
            OverallEvidence overall,
            List<Evidence> evidence,
            List<AttributionDimension> remainingDimensions,
            int currentDepth);

    ReportDecision report(
            EffectiveRequest request,
            OverallEvidence overall,
            List<Evidence> evidence,
            List<PathNode> primaryPath,
            StopInfo stop);

    String engineLabel(String requestedModel);

    record PlanDecision(
            String hypothesis,
            List<String> dimensions,
            String reason,
            LlmResultMessage llmMessage) implements Serializable {
    }

    record NextDecision(
            boolean shouldContinue,
            String selectedEvidenceId,
            String selectedMember,
            String nextDimension,
            String hypothesis,
            String reason,
            LlmResultMessage llmMessage) implements Serializable {
    }

    record ReportDecision(AttributionReport report, LlmResultMessage llmMessage) implements Serializable {
    }
}
