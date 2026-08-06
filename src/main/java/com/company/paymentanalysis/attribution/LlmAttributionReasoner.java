package com.company.paymentanalysis.attribution;

import com.company.paymentanalysis.attribution.AttributionCatalog.AttributionDimension;
import com.company.paymentanalysis.attribution.AttributionModels.AttributionReport;
import com.company.paymentanalysis.attribution.AttributionModels.EffectiveRequest;
import com.company.paymentanalysis.attribution.AttributionModels.Evidence;
import com.company.paymentanalysis.attribution.AttributionModels.OverallEvidence;
import com.company.paymentanalysis.attribution.AttributionModels.PathNode;
import com.company.paymentanalysis.attribution.AttributionModels.StopInfo;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient.ChatMessage;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient.LlmResultMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
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
    public PlanDecision plan(
            EffectiveRequest request, List<AttributionDimension> candidates, int maxDimensions) {
        List<String> fallbackDimensions = prioritized(candidates).stream()
                .limit(maxDimensions)
                .map(AttributionDimension::id)
                .toList();
        RawPlan fallback = new RawPlan(
                "从多个独立业务视角探索整体变化的主要驱动",
                fallbackDimensions,
                "优先探索机构、地域和交易介质，避免预设固定下钻链路");
        LlmResultMessage message = complete(
                request,
                """
                你是支付数据归因规划器。只能输出 JSON，不要计算任何金额、变化率或贡献度。
                从提供的候选维度中选择最多 %d 个首轮探索维度，不得创造字段。
                输出字段必须且只能为 hypothesis、dimensions、reason。
                """.formatted(maxDimensions),
                Map.of("request", request, "candidateDimensions", candidates),
                fallback);
        RawPlan parsed = parse(message.content(), RawPlan.class);
        List<String> dimensions = parsed.dimensions() == null ? List.of() : List.copyOf(parsed.dimensions());
        java.util.Set<String> allowed = candidates.stream()
                .map(AttributionDimension::id)
                .collect(java.util.stream.Collectors.toSet());
        if (dimensions.isEmpty()
                || dimensions.size() > maxDimensions
                || new LinkedHashSet<>(dimensions).size() != dimensions.size()
                || dimensions.stream().anyMatch(id -> !allowed.contains(id))) {
            throw new IllegalStateException("LLM 返回了非法或重复的首轮归因维度");
        }
        return new PlanDecision(
                requiredText(parsed.hypothesis(), "首轮归因假设"),
                dimensions,
                requiredText(parsed.reason(), "首轮规划理由"),
                message);
    }

    @Override
    public NextDecision next(
            EffectiveRequest request,
            OverallEvidence overall,
            List<Evidence> evidence,
            List<AttributionDimension> remainingDimensions,
            int currentDepth) {
        Evidence selected = evidence.stream()
                .filter(item -> item.depth() == currentDepth && item.primaryDriver() != null)
                .filter(item -> item.primaryDriver().alignedWithOverall())
                .max(Comparator.comparing(item -> item.primaryDriver().contributionRate().abs()))
                .orElse(null);
        String nextDimension = fallbackNextDimension(selected, remainingDimensions);
        RawNext fallback = selected == null || nextDimension == null
                ? new RawNext(false, null, null, null, "当前证据没有合法的继续方向", "停止")
                : new RawNext(
                        true,
                        selected.id(),
                        selected.primaryDriver().memberValue(),
                        nextDimension,
                        "在“" + selected.primaryDriver().memberValue() + "”范围内继续验证下一层原因",
                        "该成员与整体方向一致且贡献度最大，下一维度尚未探索");
        LlmResultMessage message = complete(
                request,
                """
                你是支付数据归因推理器。只能基于程序提供的 Evidence 做选择，不得重新计算或修改数值。
                如果值得继续，必须选择一个真实 evidenceId、该 Evidence 中的 memberValue，以及一个候选 nextDimension。
                不同维度是同一批数据的观察视角，贡献度不能跨维度相加。
                输出字段必须且只能为 shouldContinue、selectedEvidenceId、selectedMember、nextDimension、hypothesis、reason。
                """,
                Map.of(
                        "overall", overall,
                        "currentDepthEvidence", evidence.stream().filter(item -> item.depth() == currentDepth).toList(),
                        "candidateNextDimensions", remainingDimensions),
                fallback);
        RawNext parsed = parse(message.content(), RawNext.class);
        String reason = requiredText(parsed.reason(), "归因推理理由");
        String hypothesis = requiredText(parsed.hypothesis(), "下一步归因假设");
        return new NextDecision(
                parsed.shouldContinue(),
                parsed.selectedEvidenceId(),
                parsed.selectedMember(),
                parsed.nextDimension(),
                hypothesis,
                reason,
                message);
    }

    @Override
    public ReportDecision report(
            EffectiveRequest request,
            OverallEvidence overall,
            List<Evidence> evidence,
            List<PathNode> primaryPath,
            StopInfo stop) {
        List<String> findings = primaryPath.stream()
                .map(node -> "第" + node.depth() + "层：" + node.dimensionName() + "“" + node.memberValue()
                        + "”，贡献度 " + node.contributionRate() + "%")
                .toList();
        AttributionReport fallback = new AttributionReport(
                AttributionCatalog.metricName(request.metricId()) + "从 " + request.comparisonPeriod()
                        + " 到 " + request.currentPeriod() + " 的变化方向为 " + overall.direction()
                        + "，主要原因路径为 " + pathText(primaryPath),
                findings,
                List.of("结合主要原因路径开展业务核查", "不要将不同一级维度的贡献度相互累加"));
        LlmResultMessage message = complete(
                request,
                """
                你是支付数据归因报告撰写器。只能解释程序给出的数值和原因路径，不得自行计算或创造数字。
                输出字段必须且只能为 summary、findings、recommendations，后两者为字符串数组。
                """,
                Map.of("overall", overall, "evidence", evidence, "primaryPath", primaryPath, "stop", stop),
                fallback);
        AttributionReport parsed = parse(message.content(), AttributionReport.class);
        AttributionReport report = new AttributionReport(
                requiredText(parsed.summary(), "归因报告摘要"),
                parsed.findings() == null ? List.of() : List.copyOf(parsed.findings()),
                parsed.recommendations() == null ? List.of() : List.copyOf(parsed.recommendations()));
        return new ReportDecision(report, message);
    }

    @Override
    public String engineLabel(String requestedModel) {
        return llmClient.modelLabel(requestedModel);
    }

    private LlmResultMessage complete(
            EffectiveRequest request, String system, Object payload, Object fallback) {
        try {
            return llmClient.completeWithMessage(
                    List.of(
                            new ChatMessage("system", system),
                            new ChatMessage("user", objectMapper.writeValueAsString(payload))),
                    objectMapper.writeValueAsString(fallback),
                    request.model());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("归因 Reasoner JSON 序列化失败", exception);
        }
    }

    private <T> T parse(String content, Class<T> type) {
        try {
            return objectMapper.readValue(stripFence(content), type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("归因 Reasoner 返回的 JSON 无法解析", exception);
        }
    }

    private List<AttributionDimension> prioritized(List<AttributionDimension> candidates) {
        List<String> priority = List.of("acq_ins_ch", "iss_sc_ch", "JYJZ_NAME");
        List<AttributionDimension> result = new ArrayList<>();
        priority.forEach(id -> candidates.stream().filter(item -> item.id().equals(id)).findFirst().ifPresent(result::add));
        candidates.stream().filter(item -> !result.contains(item)).forEach(result::add);
        return result;
    }

    private String fallbackNextDimension(Evidence selected, List<AttributionDimension> remaining) {
        if (selected == null || remaining.isEmpty()) {
            return null;
        }
        List<String> preferred = switch (selected.dimensionId()) {
            case "acq_ins_ch" -> List.of("iss_sc_ch", "JYJZ_NAME", "channel_def");
            case "iss_sc_ch" -> List.of("acq_ins_ch", "JYJZ_NAME", "channel_def");
            default -> List.of("acq_ins_ch", "iss_sc_ch", "channel_def");
        };
        for (String id : preferred) {
            if (remaining.stream().anyMatch(item -> item.id().equals(id))) {
                return id;
            }
        }
        return remaining.get(0).id();
    }

    private String pathText(List<PathNode> path) {
        return path.isEmpty()
                ? "未发现显著原因路径"
                : path.stream().map(PathNode::memberValue).reduce((left, right) -> left + " → " + right).orElse("");
    }

    private String stripFence(String content) {
        String trimmed = content == null ? "" : content.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstNewline = trimmed.indexOf('\n');
        int lastFence = trimmed.lastIndexOf("```");
        return firstNewline < 0 || lastFence <= firstNewline
                ? trimmed
                : trimmed.substring(firstNewline + 1, lastFence).trim();
    }

    private String requiredText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("LLM 返回中缺少" + name);
        }
        return value.trim();
    }

    private record RawPlan(String hypothesis, List<String> dimensions, String reason) {
    }

    private record RawNext(
            boolean shouldContinue,
            String selectedEvidenceId,
            String selectedMember,
            String nextDimension,
            String hypothesis,
            String reason) {
    }
}
