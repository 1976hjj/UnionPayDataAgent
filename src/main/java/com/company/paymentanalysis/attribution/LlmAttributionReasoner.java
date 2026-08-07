package com.company.paymentanalysis.attribution;

import com.company.paymentanalysis.attribution.AttributionCatalog.AttributionDimension;
import com.company.paymentanalysis.attribution.AttributionModels.AnalysisBranch;
import com.company.paymentanalysis.attribution.AttributionModels.AttributionReport;
import com.company.paymentanalysis.attribution.AttributionModels.BranchAction;
import com.company.paymentanalysis.attribution.AttributionModels.EffectiveRequest;
import com.company.paymentanalysis.attribution.AttributionModels.Evidence;
import com.company.paymentanalysis.attribution.AttributionModels.MemberEvidence;
import com.company.paymentanalysis.attribution.AttributionModels.OverallEvidence;
import com.company.paymentanalysis.attribution.AttributionModels.PathNode;
import com.company.paymentanalysis.attribution.AttributionModels.StopInfo;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient.ChatMessage;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient.LlmResultMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class LlmAttributionReasoner implements AttributionReasoner {

    private final OpenAiCompatibleLlmClient llmClient;
    private final ObjectMapper objectMapper;

    public LlmAttributionReasoner(OpenAiCompatibleLlmClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public PlanDecision plan(EffectiveRequest request, List<AttributionDimension> candidates, int maxDimensions) {
        List<String> fallbackDimensions = candidates.stream()
                .map(AttributionDimension::id).sorted().limit(maxDimensions).toList();
        RawPlan fallback = new RawPlan(
                "从多个独立业务视角探索整体变化的主要驱动", fallbackDimensions, "按元数据白名单选择互补视角");
        LlmResultMessage message = complete(request, """
                你是支付数据归因规划器。只能输出 JSON，不要计算任何金额、变化率或贡献度。
                从提供的候选维度中选择最多 %d 个首轮探索维度，不得创造字段；优先选择业务含义互补的视角。
                输出字段必须且只能为 hypothesis、dimensions、reason。
                """.formatted(maxDimensions), Map.of("request", request, "candidateDimensions", candidates), fallback);
        RawPlan parsed = parse(message.content(), RawPlan.class);
        List<String> dimensions = parsed.dimensions() == null ? List.of() : List.copyOf(parsed.dimensions());
        var allowed = candidates.stream().map(AttributionDimension::id).collect(java.util.stream.Collectors.toSet());
        boolean valid = !dimensions.isEmpty() && dimensions.size() <= maxDimensions
                && new LinkedHashSet<>(dimensions).size() == dimensions.size()
                && dimensions.stream().allMatch(allowed::contains);
        if (!valid) {
            return new PlanDecision(
                    fallback.hypothesis(), fallbackDimensions,
                    "LLM 首轮维度不在元数据白名单内；Java 已回退到确定性的互补候选维度", message);
        }
        return new PlanDecision(requiredText(parsed.hypothesis(), "首轮归因假设"), dimensions,
                requiredText(parsed.reason(), "首轮规划理由"), message);
    }

    @Override
    public ReflectionDecision reflect(
            EffectiveRequest request,
            OverallEvidence overall,
            List<Evidence> currentEvidence,
            List<AnalysisBranch> branches,
            List<AttributionDimension> remainingDimensions,
            int remainingQueryBudget,
            int maxActions) {
        RawReflection fallback = fallbackReflection(currentEvidence, remainingDimensions, maxActions);
        LlmResultMessage message = complete(request, """
                你是支付数据归因研究策略器。只能基于程序给出的 Evidence、分支状态和候选维度制定有限调查动作，不得计算、修改或编造数值。
                不同维度是同一批数据的观察视角，贡献度不能跨维度相加；避免只换标签的重复解释。
                action 只能是 EXPAND、HOLD、STOP；role 只能是 MAIN、SECONDARY、OFFSET、UNRESOLVED；priority 只能是 HIGH、MEDIUM、LOW。
                EXPAND 必须选择真实 evidenceId、该 Evidence 中的 memberValue 和候选 nextDimension。最多输出 %d 个动作，且受剩余查询预算 %d 约束。
                输出字段必须且只能为 reflection、actions。actions 中每项必须且只能为 action、role、selectedEvidenceId、selectedMember、nextDimension、priority、hypothesis、reason。
                """.formatted(maxActions, remainingQueryBudget),
                Map.of("overall", overall, "currentEvidence", currentEvidence, "branches", branches,
                        "candidateNextDimensions", remainingDimensions, "remainingQueryBudget", remainingQueryBudget), fallback);
        RawReflection parsed = parse(message.content(), RawReflection.class);
        return new ReflectionDecision(requiredText(parsed.reflection(), "分支反思"),
                parsed.actions() == null ? List.of() : parsed.actions(), message);
    }

    @Override
    public ReportDecision report(
            EffectiveRequest request,
            OverallEvidence overall,
            List<Evidence> evidence,
            List<PathNode> primaryPath,
            List<AnalysisBranch> branches,
            StopInfo stop) {
        AttributionReport fallback = new AttributionReport(
                AttributionCatalog.metricName(request.metricId()) + "从 " + request.comparisonPeriod() + " 到 "
                        + request.currentPeriod() + " 的变化方向为 " + overall.direction(),
                branches.stream().map(branch -> branch.role() + "：" + pathText(branch.path())).toList(),
                List.of("结合已验证分支开展业务核查", "不要将不同维度的贡献度相互累加"));
        LlmResultMessage message = complete(request, """
                你是支付数据归因报告撰写器。只能解释程序给出的数值、分支和 Evidence，不得自行计算或创造数字。
                将主因、次因、抵消因素和未决项区分表达，并说明停止原因。
                输出字段必须且只能为 summary、findings、recommendations，后两者为字符串数组。
                """, Map.of("overall", overall, "evidence", evidence, "primaryPath", primaryPath,
                "branches", branches, "stop", stop), fallback);
        AttributionReport parsed = parse(message.content(), AttributionReport.class);
        return new ReportDecision(new AttributionReport(requiredText(parsed.summary(), "归因报告摘要"),
                parsed.findings() == null ? List.of() : List.copyOf(parsed.findings()),
                parsed.recommendations() == null ? List.of() : List.copyOf(parsed.recommendations())), message);
    }

    @Override
    public String engineLabel(String requestedModel) { return llmClient.modelLabel(requestedModel); }

    private RawReflection fallbackReflection(
            List<Evidence> evidence, List<AttributionDimension> dimensions, int maxActions) {
        if (dimensions.isEmpty()) return new RawReflection("没有剩余可探索维度，停止调查", List.of());
        List<BranchAction> actions = evidence.stream()
                .filter(item -> item.primaryDriver() != null && item.primaryDriver().alignedWithOverall())
                .sorted(Comparator.comparing((Evidence item) -> item.primaryDriver().contributionRate().abs()).reversed())
                .limit(maxActions)
                .map(item -> new BranchAction("EXPAND", "MAIN", item.id(), item.primaryDriver().memberValue(),
                        dimensions.get(0).id(), "HIGH", "继续验证该驱动在另一观察视角中的构成", "选择真实 Evidence 中方向一致的显著成员"))
                .toList();
        return new RawReflection(actions.isEmpty() ? "当前没有可继续的同方向驱动" : "按显著同方向候选进行有限扩展", actions);
    }

    private LlmResultMessage complete(EffectiveRequest request, String system, Object payload, Object fallback) {
        try {
            return llmClient.completeWithMessage(List.of(new ChatMessage("system", system),
                    new ChatMessage("user", objectMapper.writeValueAsString(payload))),
                    objectMapper.writeValueAsString(fallback), request.model());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("归因 Reasoner JSON 序列化失败", exception);
        }
    }

    private <T> T parse(String content, Class<T> type) {
        try { return objectMapper.readValue(stripFence(content), type); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("归因 Reasoner 返回的 JSON 无法解析", exception); }
    }
    private String stripFence(String content) {
        String trimmed = content == null ? "" : content.trim();
        if (!trimmed.startsWith("```")) return trimmed;
        int first = trimmed.indexOf('\n'); int last = trimmed.lastIndexOf("```");
        return first < 0 || last <= first ? trimmed : trimmed.substring(first + 1, last).trim();
    }
    private String requiredText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalStateException("LLM 返回中缺少" + name);
        return value.trim();
    }
    private String pathText(List<PathNode> path) {
        return path == null || path.isEmpty() ? "未形成下钻路径"
                : path.stream().map(PathNode::memberValue).reduce((a, b) -> a + " → " + b).orElse("");
    }
    private record RawPlan(String hypothesis, List<String> dimensions, String reason) { }
    private record RawReflection(String reflection, List<BranchAction> actions) { }
}
