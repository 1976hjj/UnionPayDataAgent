package com.company.paymentanalysis.attribution;

import com.company.paymentanalysis.attribution.AttributionModels.AttributionRequest;
import com.company.paymentanalysis.attribution.AttributionModels.AttributionResponse;
import com.company.paymentanalysis.attribution.AttributionModels.EffectiveRequest;
import com.company.paymentanalysis.attribution.AttributionWorkflowService.WorkflowObserver;
import com.company.paymentanalysis.artifact.model.Artifact;
import com.company.paymentanalysis.artifact.model.AttributionResultArtifactPayload;
import com.company.paymentanalysis.artifact.model.AttributionResultArtifactPayload.AttributionContract;
import com.company.paymentanalysis.artifact.model.AttributionResultArtifactPayload.Overall;
import com.company.paymentanalysis.artifact.service.ArtifactService;
import com.company.paymentanalysis.artifact.service.ArtifactService.CreateAttributionResult;
import com.company.paymentanalysis.chat.ChatConversationMemoryService;
import com.company.paymentanalysis.chat.ConversationArtifact.VerifiedFact;
import com.company.paymentanalysis.permission.DataPermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Shared execution boundary for every attribution caller.
 * Validation, workflow execution, and the resulting conversation artifact stay
 * together so the legacy controller and unified Agent cannot diverge.
 */
@Service
public class AttributionExecutionService {

    private final AttributionRequestValidator requestValidator;
    private final AttributionWorkflowService workflowService;
    private final ChatConversationMemoryService memoryService;
    private final DataPermissionService permissionService;
    private final ArtifactService artifactService;
    private final ObjectMapper objectMapper;

    public AttributionExecutionService(
            AttributionRequestValidator requestValidator,
            AttributionWorkflowService workflowService,
            ChatConversationMemoryService memoryService,
            DataPermissionService permissionService,
            ArtifactService artifactService,
            ObjectMapper objectMapper) {
        this.requestValidator = requestValidator;
        this.workflowService = workflowService;
        this.memoryService = memoryService;
        this.permissionService = permissionService;
        this.artifactService = artifactService;
        this.objectMapper = objectMapper;
    }

    public ExecutedAttribution execute(AttributionRequest request) {
        return execute(request, event -> { });
    }

    public ExecutedAttribution execute(AttributionRequest request, WorkflowObserver observer) {
        EffectiveRequest validatedRequest = requestValidator.validate(request);
        EffectiveRequest effectiveRequest = validatedRequest.withPermissionScope(
                permissionService.resolveRequiredScope(validatedRequest.loginUsername()));
        AttributionResponse response = workflowService.analyze(effectiveRequest, observer);
        String artifactId = saveArtifacts(request, effectiveRequest, response);
        return new ExecutedAttribution(effectiveRequest, response, artifactId);
    }

    private String saveArtifacts(
            AttributionRequest request, EffectiveRequest effectiveRequest, AttributionResponse response) {
        String conversationId = safeIdentifier(request.conversationId());
        if (conversationId == null || response.report() == null || response.overall() == null) return null;
        String userId = safeIdentifier(request.userId());
        userId = userId == null ? "demo-user" : userId;
        String title = response.metricName() + "归因：" + response.currentPeriod()
                + " 对比 " + response.comparisonPeriod();
        Artifact unifiedArtifact = artifactService.createAttributionResult(new CreateAttributionResult(
                userId, conversationId, null, title,
                attributionPayload(effectiveRequest, response)));
        String filters = effectiveRequest.dimensionFilters().stream()
                .map(filter -> filter.dimensionId() + " " + filter.operator() + " " + filter.values())
                .collect(Collectors.joining("；"));
        String contract = "指标=" + response.metricName() + "(" + response.metricId() + ")；周期="
                + response.currentPeriod() + " 对比 " + response.comparisonPeriod()
                + (filters.isBlank() ? "" : "；过滤=" + filters)
                + "；深度=" + effectiveRequest.maxDepth() + "；最大查询=" + effectiveRequest.maxQueries()
                + "；TopN=" + effectiveRequest.topN();
        String path = response.primaryPath().stream()
                .map(node -> node.dimensionName() + "=" + node.memberValue())
                .collect(Collectors.joining(" → "));
        String evidence = "主路径=" + (path.isBlank() ? "未形成下钻路径" : path);
        String modelNarrative = "摘要=" + safe(response.report().summary())
                + "；关键发现=" + String.join("；", response.report().findings())
                + "；建议=" + String.join("；", response.report().recommendations());
        Map<String, String> attributes = Map.of(
                "metricId", response.metricId(),
                "metricName", response.metricName(),
                "currentPeriod", response.currentPeriod(),
                "comparisonPeriod", response.comparisonPeriod());
        memoryService.saveAttributionArtifact(
                userId, conversationId, title,
                safe(response.report().summary()), contract, evidence, attributes,
                verifiedFacts(response), modelNarrative);
        return unifiedArtifact == null ? null : unifiedArtifact.artifactId();
    }

    private AttributionResultArtifactPayload attributionPayload(
            EffectiveRequest request, AttributionResponse response) {
        Overall overall = new Overall(
                response.overall().currentValue(), response.overall().comparisonValue(),
                response.overall().changeAmount(), response.overall().changeRate(),
                response.overall().direction());
        AttributionContract contract = new AttributionContract(
                request.dimensionFilters().stream()
                        .map(filter -> new AttributionResultArtifactPayload.Filter(
                                filter.dimensionId(), filter.operator(), filter.values()))
                        .toList(),
                request.maxDepth(), request.maxQueries(), request.topN(), request.maxBranches());
        return new AttributionResultArtifactPayload(
                response.metricId(), response.metricName(), response.currentPeriod(),
                response.comparisonPeriod(), overall, safe(response.report().summary()),
                response.report().findings(), response.report().recommendations(), contract,
                objectMapper.valueToTree(response));
    }

    private List<VerifiedFact> verifiedFacts(AttributionResponse response) {
        List<VerifiedFact> facts = new ArrayList<>();
        facts.add(fact("metric", "分析指标", response.metricName(), "REQUEST_VALIDATION"));
        facts.add(fact("period.current", "当前周期", response.currentPeriod(), "REQUEST_VALIDATION"));
        facts.add(fact("period.comparison", "对比周期", response.comparisonPeriod(), "REQUEST_VALIDATION"));
        facts.add(fact("overall.current", "当前值", number(response.overall().currentValue()), "JAVA_OVERALL"));
        facts.add(fact("overall.comparison", "对比值", number(response.overall().comparisonValue()), "JAVA_OVERALL"));
        facts.add(fact("overall.changeAmount", "变化额", number(response.overall().changeAmount()), "JAVA_OVERALL"));
        facts.add(fact("overall.changeRate", "变化率", number(response.overall().changeRate()), "JAVA_OVERALL"));
        facts.add(fact("overall.direction", "变化方向", response.overall().direction(), "JAVA_OVERALL"));
        for (var node : response.primaryPath()) {
            String prefix = "path." + node.depth();
            facts.add(fact(prefix + ".member", "主路径第" + node.depth() + "层",
                    node.dimensionName() + "=" + node.memberValue(), "JAVA_PRIMARY_PATH"));
            facts.add(fact(prefix + ".changeAmount", "该层变化额",
                    number(node.changeAmount()), "JAVA_PRIMARY_PATH"));
            facts.add(fact(prefix + ".contributionRate", "该层贡献率",
                    number(node.contributionRate()), "JAVA_PRIMARY_PATH"));
        }
        response.evidence().stream()
                .filter(item -> "VALID".equals(item.dataStatus()) && item.primaryDriver() != null)
                .forEach(item -> facts.add(fact(
                        "evidence." + item.id() + ".primaryDriver",
                        item.dimensionName() + "主要驱动",
                        item.primaryDriver().memberValue() + "；变化额="
                                + number(item.primaryDriver().changeAmount()) + "；贡献率="
                                + number(item.primaryDriver().contributionRate()),
                        "JAVA_EVIDENCE")));
        return List.copyOf(facts);
    }

    private VerifiedFact fact(String code, String label, String value, String source) {
        return new VerifiedFact(code, label, value, source);
    }

    private String number(BigDecimal value) {
        return value == null ? "不可用" : value.stripTrailingZeros().toPlainString();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String safeIdentifier(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        return normalized.length() <= 80 && normalized.matches("[A-Za-z0-9._-]+") ? normalized : null;
    }

    public record ExecutedAttribution(
            EffectiveRequest effectiveRequest, AttributionResponse response, String artifactId) {

        public ExecutedAttribution(EffectiveRequest effectiveRequest, AttributionResponse response) {
            this(effectiveRequest, response, null);
        }
    }
}
