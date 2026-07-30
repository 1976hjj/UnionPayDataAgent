package com.company.paymentanalysis.chat;

import com.company.paymentanalysis.controller.ChatQueryController.ChatRequest;
import com.company.paymentanalysis.controller.ChatQueryController.QueryContext;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient.ChatMessage;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient.LlmResultMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import org.springframework.stereotype.Component;

@Component
public class ChatQueryInterpreter {

    private static final Set<String> INTENTS = Set.of("QUERY", "GREETING", "RESET", "OUT_OF_SCOPE");
    private static final Set<String> PERIOD_ACTIONS = Set.of("KEEP", "SET", "CLEAR");
    private static final Set<String> LIST_ACTIONS = Set.of("KEEP", "ADD", "REMOVE", "CLEAR");
    private static final Set<String> METRICS =
            Set.of("transactionAmount", "transactionCount", "successRate");
    private static final Set<String> DIMENSIONS =
            Set.of(
                    "tradeYear", "tradeMonth", "tradeDate",
                    "channel", "region", "merchantType", "paymentMethod");
    private static final List<String> REMOVAL_WORDS = List.of("取消", "移除", "去掉", "删除", "不要");
    private static final List<String> ADDITION_WORDS = List.of("增加", "新增", "追加", "加上");
    private static final List<String> EDIT_WORDS = List.of(
            "取消", "移除", "去掉", "删除", "不要",
            "增加", "新增", "追加", "加上",
            "改为按", "换成按", "取代", "替换");

    private final OpenAiCompatibleLlmClient llmClient;
    private final ObjectMapper objectMapper;

    public ChatQueryInterpreter(OpenAiCompatibleLlmClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    public InterpretationResult interpret(ChatRequest request, QueryContext current) {
        Interpretation deterministic = deterministicInterpretation(request.message(), current);
        LlmResultMessage llmMessage = null;
        try {
            String mockJson = objectMapper.writeValueAsString(deterministic);
            llmMessage = llmClient.completeWithMessage(
                    List.of(
                            new ChatMessage(
                                    "system",
                                    """
                                    你是支付数据查数意图解析器，只能处理查数。
                                    只返回 JSON，不要解释或 Markdown。
                                    intent 只能是 QUERY、GREETING、RESET、OUT_OF_SCOPE。
                                    periodAction 只能是 KEEP、SET、CLEAR。
                                    JSON 必须始终包含 intent、periodAction、startDate、endDate、periodLabel、
                                    metricAction、dimensionAction 这七个字段，不得省略。
                                    periodAction 为 SET 时 startDate、endDate、periodLabel 必须是非空字符串；
                                    periodAction 为 KEEP 或 CLEAR 时这三个字段返回空字符串。
                                    metricAction 和 dimensionAction 都是对象，格式为：
                                    {"operations":[{"action":"ADD","ids":["..."]},{"action":"REMOVE","ids":["..."]}]}
                                    action 只能是 KEEP、ADD、REMOVE、CLEAR，禁止返回 REPLACE。
                                    KEEP 表示不改变，CLEAR 表示全部清空；KEEP 和 CLEAR 都必须单独出现，且 ids 必须为空。
                                    ADD 和 REMOVE 可以单独出现，也可以同时出现；每个动作自己的 ids 表示该动作要改变的项目。
                                    同一种动作最多出现一次。没有任何改变时返回 {"operations":[{"action":"KEEP","ids":[]}]}。
                                    metricAction 中的 ids 只能使用 transactionAmount、transactionCount、successRate。
                                    dimensionAction 中的 ids 只能使用 tradeYear、tradeMonth、tradeDate、
                                    channel、region、merchantType、paymentMethod。
                                    用户要求“按年/每年”时使用 tradeYear，“按月/每月/每个月”时使用 tradeMonth，
                                    “按日/每天/日期维度”时使用 tradeDate。
                                    当前日期固定为 2026-07-30；“本月”为 2026-07-01 至 2026-07-30，
                                    “上月”为 2026-06-01 至 2026-06-30。
                                    “近三个月”固定为 2026-05-01 至 2026-07-30。
                                    用户要求“近三个月”时必须返回：
                                    "periodAction":"SET","startDate":"2026-05-01",
                                    "endDate":"2026-07-30","periodLabel":"近三个月"。
                                    不要把用户原文、提示词或任何密钥放进 explanation。
                                    """),
                            new ChatMessage(
                                    "user",
                                    "当前上下文："
                                            + objectMapper.writeValueAsString(current)
                                            + "\n用户本轮输入："
                                            + request.message())),
                    mockJson);
            Interpretation interpretation = parseInterpretation(llmMessage.content());
            return new InterpretationResult(validateAndNormalize(interpretation, deterministic), llmMessage);
        } catch (JsonProcessingException exception) {
            return new InterpretationResult(deterministic, llmMessage);
        }
    }

    public String engineLabel() {
        return llmClient.modelLabel();
    }

    /**
     * Compatibility bridge for the existing ADD/REMOVE/CLEAR conversation merge rules.
     * Intent recognition and SmartBI planning are handled by the new analysis pipeline.
     */
    public Interpretation interpretForContextMerge(String message, QueryContext current) {
        return deterministicInterpretation(message, current);
    }

    private Interpretation validateAndNormalize(Interpretation value, Interpretation fallback) {
        if (value == null) {
            return fallback;
        }
        String intent = allowedOrFallback(value.intent(), INTENTS, fallback.intent());
        String periodAction =
                allowedOrFallback(value.periodAction(), PERIOD_ACTIONS, fallback.periodAction());
        ActionPlan metricAction = normalizeActionPlan(value.metricAction(), fallback.metricAction(), METRICS, 3);
        ActionPlan dimensionAction =
                normalizeActionPlan(value.dimensionAction(), fallback.dimensionAction(), DIMENSIONS, 7);
        if (hasChange(fallback.metricAction())) {
            metricAction = fallback.metricAction();
        }
        if (hasChange(fallback.dimensionAction())) {
            dimensionAction = fallback.dimensionAction();
        }
        return new Interpretation(
                intent,
                periodAction,
                value.startDate() == null ? fallback.startDate() : value.startDate(),
                value.endDate() == null ? fallback.endDate() : value.endDate(),
                value.periodLabel() == null ? fallback.periodLabel() : value.periodLabel(),
                metricAction,
                dimensionAction,
                value.explanation() == null ? fallback.explanation() : value.explanation());
    }

    private String allowedOrFallback(String value, Set<String> allowed, String fallback) {
        return value != null && allowed.contains(value) ? value : fallback;
    }

    private Interpretation deterministicInterpretation(String message, QueryContext current) {
        if (message.contains("重新查询")
                || message.contains("重来")
                || message.contains("清空查询")
                || message.contains("清空条件")
                || "清空".equals(message.trim())) {
            return new Interpretation(
                    "RESET", "CLEAR", "", "", "", clearPlan(), clearPlan(), "清空当前查询条件");
        }
        if (isGreeting(message) && current.isEmpty()) {
            return new Interpretation(
                    "GREETING", "KEEP", "", "", "", keepPlan(), keepPlan(), "支付查数问候");
        }
        if (!isQueryRelated(message)) {
            return new Interpretation(
                    "OUT_OF_SCOPE", "KEEP", "", "", "", keepPlan(), keepPlan(), "非支付查数请求");
        }

        String periodAction = "KEEP";
        String startDate = "";
        String endDate = "";
        String periodLabel = "";
        if (message.contains("最近7天") || message.contains("近7天")) {
            periodAction = "SET";
            startDate = "2026-07-24";
            endDate = "2026-07-30";
            periodLabel = "最近7天";
        } else if (message.contains("上月") || message.contains("6月") || message.contains("六月")) {
            periodAction = "SET";
            startDate = "2026-06-01";
            endDate = "2026-06-30";
            periodLabel = "2026年6月";
        } else if (message.contains("本月") || message.contains("7月") || message.contains("七月")) {
            periodAction = "SET";
            startDate = "2026-07-01";
            endDate = "2026-07-30";
            periodLabel = "2026年7月";
        }

        List<String> metrics = detectMetrics(message);
        ActionPlan metricAction;
        if (message.contains("清空全部度量")
                || message.contains("清空所有度量")
                || message.contains("清空全部指标")
                || message.contains("清空所有指标")
                || message.contains("不要任何指标")) {
            metricAction = clearPlan();
        } else {
            List<String> metricRemovals =
                    idsFollowingActions(message, REMOVAL_WORDS, this::detectMetrics);
            List<String> metricAdditions =
                    idsFollowingActions(message, ADDITION_WORDS, this::detectMetrics);
            ListEdits metricSubstitution = substitutionEdits(message, this::detectMetrics);
            if (!metricSubstitution.removals().isEmpty() || !metricSubstitution.additions().isEmpty()) {
                metricRemovals = metricSubstitution.removals();
                metricAdditions = metricSubstitution.additions();
            }
            if (containsRemovalWord(message) && metricRemovals.isEmpty()) {
                metricRemovals = excluding(metrics, metricAdditions);
            }
            if (containsAdditionWord(message) && metricAdditions.isEmpty()) {
                metricAdditions = excluding(metrics, metricRemovals);
            }
            if (!metricAdditions.isEmpty() || !metricRemovals.isEmpty()) {
                metricAction = editPlan(metricAdditions, metricRemovals);
            } else if (!metrics.isEmpty() && (message.contains("只看") || message.contains("改为"))) {
                metricAction = replacementPlan(current.metricIds(), metrics);
            } else {
                metricAction = editPlan(metrics, List.of());
            }
        }

        List<String> mentionedDimensions = detectDimensions(message);
        ActionPlan dimensionAction;
        if (message.contains("不分维度")
                || message.contains("看汇总")
                || message.contains("清空全部维度")
                || message.contains("清空所有维度")
                || message.contains("清空维度")) {
            dimensionAction = clearPlan();
        } else {
            List<String> removals =
                    idsFollowingActions(message, REMOVAL_WORDS, this::detectDimensions);
            List<String> additions =
                    idsFollowingActions(message, ADDITION_WORDS, this::detectDimensions);
            ListEdits substitution = substitutionEdits(message, this::detectDimensions);
            if (!substitution.removals().isEmpty() || !substitution.additions().isEmpty()) {
                removals = substitution.removals();
                additions = substitution.additions();
            }
            if (containsRemovalWord(message) && removals.isEmpty()) {
                removals = excluding(mentionedDimensions, additions);
            }
            if (containsAdditionWord(message) && additions.isEmpty()) {
                additions = excluding(mentionedDimensions, removals);
            }
            if (additions.isEmpty() && removals.isEmpty() && !mentionedDimensions.isEmpty()) {
                if (message.contains("改为按") || message.contains("换成按")) {
                    dimensionAction = replacementPlan(current.dimensionIds(), mentionedDimensions);
                } else {
                    dimensionAction = editPlan(mentionedDimensions, List.of());
                }
            } else {
                dimensionAction = editPlan(additions, removals);
            }
        }
        return new Interpretation(
                "QUERY",
                periodAction,
                startDate,
                endDate,
                periodLabel,
                metricAction,
                dimensionAction,
                "识别支付查数条件并合并多轮上下文");
    }

    private boolean isGreeting(String message) {
        return message.matches("^(你好|您好|嗨|hello|hi)[！!。.]?$");
    }

    private boolean isQueryRelated(String message) {
        String normalized = message.toLowerCase(Locale.ROOT);
        return List.of(
                        "查", "看", "数据", "交易", "金额", "笔数", "成功率", "指标", "度量",
                        "渠道", "地区", "省", "城市", "商户", "支付方式", "付款方式", "维度",
                        "日期", "按年", "每年", "按月", "每月", "每个月", "按日", "每天", "每日",
                        "本月", "上月", "最近", "月", "天", "汇总", "全部", "追加", "加上", "分组")
                .stream()
                .anyMatch(normalized::contains);
    }

    private boolean containsRemovalWord(String message) {
        return REMOVAL_WORDS.stream().anyMatch(message::contains);
    }

    private boolean containsAdditionWord(String message) {
        return ADDITION_WORDS.stream().anyMatch(message::contains);
    }

    private List<String> idsFollowingActions(
            String message, List<String> actionWords, Function<String, List<String>> detector) {
        List<String> values = new ArrayList<>();
        for (String actionWord : actionWords) {
            int fromIndex = 0;
            while ((fromIndex = message.indexOf(actionWord, fromIndex)) >= 0) {
                int start = fromIndex + actionWord.length();
                int end = nextEditWordIndex(message, start);
                values.addAll(detector.apply(message.substring(start, end)));
                fromIndex = start;
            }
        }
        return values.stream().distinct().toList();
    }

    private ListEdits substitutionEdits(
            String message, Function<String, List<String>> detector) {
        int replaceIndex = message.indexOf("取代");
        if (replaceIndex >= 0) {
            List<String> additions = detector.apply(message.substring(0, replaceIndex));
            List<String> removals = detector.apply(message.substring(replaceIndex + "取代".length()));
            return new ListEdits(removals, additions);
        }
        for (String word : List.of("替换为", "替换成")) {
            replaceIndex = message.indexOf(word);
            if (replaceIndex >= 0) {
                List<String> removals = detector.apply(message.substring(0, replaceIndex));
                List<String> additions = detector.apply(message.substring(replaceIndex + word.length()));
                return new ListEdits(removals, additions);
            }
        }
        return new ListEdits(List.of(), List.of());
    }

    private int nextEditWordIndex(String message, int start) {
        return EDIT_WORDS.stream()
                .mapToInt(word -> message.indexOf(word, start))
                .filter(index -> index >= 0)
                .min()
                .orElse(message.length());
    }

    private List<String> detectDimensions(String text) {
        List<String> dimensions = new ArrayList<>();
        if (text.contains("按年") || text.contains("每年") || text.contains("年度维度")
                || text.contains("年维度")) {
            dimensions.add("tradeYear");
        }
        if (text.contains("按月") || text.contains("每月") || text.contains("每个月")
                || text.contains("月份维度") || text.contains("月维度")) {
            dimensions.add("tradeMonth");
        }
        if (text.contains("按日") || text.contains("每天") || text.contains("每日")
                || text.contains("日期维度") || text.contains("日维度")) {
            dimensions.add("tradeDate");
        }
        if (text.contains("渠道")) {
            dimensions.add("channel");
        }
        if (text.contains("地区") || text.contains("省份") || text.contains("城市")) {
            dimensions.add("region");
        }
        if (text.contains("商户")) {
            dimensions.add("merchantType");
        }
        if (text.contains("支付方式") || text.contains("付款方式")) {
            dimensions.add("paymentMethod");
        }
        return dimensions;
    }

    private List<String> detectMetrics(String text) {
        List<String> metrics = new ArrayList<>();
        if (text.contains("全部度量") || text.contains("所有度量")
                || text.contains("全部指标") || text.contains("所有指标")) {
            return List.of("transactionAmount", "transactionCount", "successRate");
        }
        if (text.contains("金额") || text.contains("交易额")) {
            metrics.add("transactionAmount");
        }
        if (text.contains("笔数") || text.contains("交易量")) {
            metrics.add("transactionCount");
        }
        if (text.contains("成功率")) {
            metrics.add("successRate");
        }
        return List.copyOf(metrics);
    }

    private ActionPlan normalizeActionPlan(
            ActionPlan value, ActionPlan fallback, Set<String> allowedIds, int limit) {
        if (value == null || value.operations() == null) {
            return fallback;
        }
        List<ActionOperation> operations = value.operations().stream()
                .filter(operation -> operation != null && LIST_ACTIONS.contains(operation.action()))
                .toList();
        boolean hasKeep = operations.stream().anyMatch(operation -> "KEEP".equals(operation.action()));
        boolean hasClear = operations.stream().anyMatch(operation -> "CLEAR".equals(operation.action()));
        if ((hasKeep || hasClear) && operations.stream()
                .anyMatch(operation -> !operation.action().equals(hasKeep ? "KEEP" : "CLEAR"))) {
            return fallback;
        }
        if (hasKeep) {
            return keepPlan();
        }
        if (hasClear) {
            return clearPlan();
        }
        LinkedHashSet<String> additions = idsFor(operations, "ADD", allowedIds, limit);
        LinkedHashSet<String> removals = idsFor(operations, "REMOVE", allowedIds, limit);
        removals.removeAll(additions);
        return editPlan(List.copyOf(additions), List.copyOf(removals));
    }

    private LinkedHashSet<String> idsFor(
            List<ActionOperation> operations, String action, Set<String> allowedIds, int limit) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        operations.stream()
                .filter(operation -> action.equals(operation.action()))
                .flatMap(operation -> safeList(operation.ids()).stream())
                .filter(allowedIds::contains)
                .limit(limit)
                .forEach(result::add);
        return result;
    }

    private boolean hasChange(ActionPlan plan) {
        return plan != null
                && plan.operations() != null
                && plan.operations().stream()
                        .anyMatch(operation -> operation != null && !"KEEP".equals(operation.action()));
    }

    private ActionPlan replacementPlan(List<String> current, List<String> desired) {
        List<String> removals = safeList(current).stream().filter(id -> !desired.contains(id)).toList();
        List<String> additions = desired.stream().filter(id -> !safeList(current).contains(id)).toList();
        return editPlan(additions, removals);
    }

    private ActionPlan editPlan(List<String> additions, List<String> removals) {
        List<ActionOperation> operations = new ArrayList<>();
        List<String> uniqueRemovals = safeList(removals).stream().distinct().toList();
        List<String> uniqueAdditions = safeList(additions).stream().distinct().toList();
        if (!uniqueRemovals.isEmpty()) {
            operations.add(new ActionOperation("REMOVE", uniqueRemovals));
        }
        if (!uniqueAdditions.isEmpty()) {
            operations.add(new ActionOperation("ADD", uniqueAdditions));
        }
        return operations.isEmpty() ? keepPlan() : new ActionPlan(List.copyOf(operations));
    }

    private ActionPlan keepPlan() {
        return new ActionPlan(List.of(new ActionOperation("KEEP", List.of())));
    }

    private ActionPlan clearPlan() {
        return new ActionPlan(List.of(new ActionOperation("CLEAR", List.of())));
    }

    private List<String> excluding(List<String> values, List<String> excluded) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (!excluded.contains(value)) {
                result.add(value);
            }
        }
        return List.copyOf(result);
    }

    private record ListEdits(List<String> removals, List<String> additions) {
    }

    private Interpretation parseInterpretation(String content) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(stripMarkdownFence(content));
        JsonNode payload = root != null && root.path("answer").isObject() ? root.path("answer") : root;
        return objectMapper.treeToValue(payload, Interpretation.class);
    }

    private String stripMarkdownFence(String content) {
        String trimmed = content.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstNewline = trimmed.indexOf('\n');
        int lastFence = trimmed.lastIndexOf("```");
        return trimmed.substring(firstNewline + 1, lastFence).trim();
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    public record Interpretation(
            String intent,
            String periodAction,
            String startDate,
            String endDate,
            String periodLabel,
            ActionPlan metricAction,
            ActionPlan dimensionAction,
            String explanation) implements Serializable {
    }

    public record ActionPlan(List<ActionOperation> operations) implements Serializable {
    }

    public record ActionOperation(String action, List<String> ids) implements Serializable {
    }

    public record InterpretationResult(Interpretation interpretation, LlmResultMessage llmMessage) {
    }
}
