package com.company.paymentanalysis.chat;

import com.company.paymentanalysis.controller.ChatQueryController.ChatRequest;
import com.company.paymentanalysis.controller.ChatQueryController.QueryContext;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient.ChatMessage;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient.LlmResultMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ChatQueryInterpreter {

    private static final Set<String> INTENTS = Set.of("QUERY", "GREETING", "RESET", "OUT_OF_SCOPE");
    private static final Set<String> ACTIONS = Set.of("KEEP", "ADD", "REPLACE", "CLEAR", "SET");
    private static final Set<String> METRICS =
            Set.of("transactionAmount", "transactionCount", "successRate");
    private static final Set<String> DIMENSIONS =
            Set.of("channel", "region", "merchantType", "paymentMethod");

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
                                    metricAction 和 dimensionAction 只能是 KEEP、ADD、REPLACE、CLEAR。
                                    metricIds 只能使用 transactionAmount、transactionCount、successRate。
                                    dimensionIds 只能使用 channel、region、merchantType、paymentMethod。
                                    当前日期固定为 2026-07-30；“本月”为 2026-07-01 至 2026-07-30，
                                    “上月”为 2026-06-01 至 2026-06-30。
                                    不要把用户原文、提示词或任何密钥放进 explanation。
                                    """),
                            new ChatMessage(
                                    "user",
                                    "当前上下文："
                                            + objectMapper.writeValueAsString(current)
                                            + "\n用户本轮输入："
                                            + request.message()
                                            + "\n候选解析结果："
                                            + mockJson)),
                    mockJson);
            Interpretation interpretation = objectMapper.readValue(
                    stripMarkdownFence(llmMessage.content()),
                    Interpretation.class);
            return new InterpretationResult(validateAndNormalize(interpretation, deterministic), llmMessage);
        } catch (JsonProcessingException exception) {
            return new InterpretationResult(deterministic, llmMessage);
        }
    }

    public String engineLabel() {
        return llmClient.modelLabel();
    }

    private Interpretation validateAndNormalize(Interpretation value, Interpretation fallback) {
        if (value == null) {
            return fallback;
        }
        String intent = allowedOrFallback(value.intent(), INTENTS, fallback.intent());
        String periodAction =
                allowedOrFallback(value.periodAction(), ACTIONS, fallback.periodAction());
        String metricAction =
                allowedOrFallback(value.metricAction(), ACTIONS, fallback.metricAction());
        String dimensionAction =
                allowedOrFallback(value.dimensionAction(), ACTIONS, fallback.dimensionAction());
        List<String> metrics = safeList(value.metricIds() == null
                        ? fallback.metricIds()
                        : value.metricIds()).stream()
                .filter(METRICS::contains)
                .distinct()
                .limit(3)
                .toList();
        List<String> dimensions = safeList(value.dimensionIds() == null
                        ? fallback.dimensionIds()
                        : value.dimensionIds()).stream()
                .filter(DIMENSIONS::contains)
                .distinct()
                .limit(4)
                .toList();
        return new Interpretation(
                intent,
                periodAction,
                value.startDate() == null ? fallback.startDate() : value.startDate(),
                value.endDate() == null ? fallback.endDate() : value.endDate(),
                value.periodLabel() == null ? fallback.periodLabel() : value.periodLabel(),
                metricAction,
                metrics,
                dimensionAction,
                dimensions,
                value.explanation() == null ? fallback.explanation() : value.explanation());
    }

    private String allowedOrFallback(String value, Set<String> allowed, String fallback) {
        return value != null && allowed.contains(value) ? value : fallback;
    }

    private Interpretation deterministicInterpretation(String message, QueryContext current) {
        if (message.contains("重新查询") || message.contains("清空") || message.contains("重来")) {
            return new Interpretation(
                    "RESET", "CLEAR", "", "", "", "CLEAR", List.of(), "CLEAR", List.of(), "清空当前查询条件");
        }
        if (isGreeting(message) && current.isEmpty()) {
            return new Interpretation(
                    "GREETING", "KEEP", "", "", "", "KEEP", List.of(), "KEEP", List.of(), "支付查数问候");
        }
        if (!isQueryRelated(message)) {
            return new Interpretation(
                    "OUT_OF_SCOPE", "KEEP", "", "", "", "KEEP", List.of(), "KEEP", List.of(), "非支付查数请求");
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

        List<String> metrics = new ArrayList<>();
        if (message.contains("全部度量") || message.contains("所有指标")) {
            metrics.addAll(List.of("transactionAmount", "transactionCount", "successRate"));
        } else {
            if (message.contains("金额") || message.contains("交易额")) {
                metrics.add("transactionAmount");
            }
            if (message.contains("笔数") || message.contains("交易量")) {
                metrics.add("transactionCount");
            }
            if (message.contains("成功率")) {
                metrics.add("successRate");
            }
        }
        String metricAction = metrics.isEmpty()
                ? "KEEP"
                : message.contains("只看") || message.contains("改为")
                        ? "REPLACE"
                        : "ADD";

        List<String> dimensions = new ArrayList<>();
        String dimensionAction = "KEEP";
        if (message.contains("不分维度") || message.contains("看汇总")) {
            dimensionAction = "CLEAR";
        } else {
            if (message.contains("渠道")) {
                dimensions.add("channel");
            }
            if (message.contains("地区") || message.contains("省份") || message.contains("城市")) {
                dimensions.add("region");
            }
            if (message.contains("商户")) {
                dimensions.add("merchantType");
            }
            if (message.contains("支付方式") || message.contains("付款方式")) {
                dimensions.add("paymentMethod");
            }
            if (!dimensions.isEmpty()) {
                dimensionAction = message.contains("改为按") || message.contains("换成按")
                        ? "REPLACE"
                        : "ADD";
            }
        }
        return new Interpretation(
                "QUERY",
                periodAction,
                startDate,
                endDate,
                periodLabel,
                metricAction,
                metrics,
                dimensionAction,
                dimensions,
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
                        "本月", "上月", "最近", "月", "天", "汇总", "全部", "追加", "加上", "分组")
                .stream()
                .anyMatch(normalized::contains);
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
            String metricAction,
            List<String> metricIds,
            String dimensionAction,
            List<String> dimensionIds,
            String explanation) implements Serializable {
    }

    public record InterpretationResult(Interpretation interpretation, LlmResultMessage llmMessage) {
    }
}
