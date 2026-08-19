package com.company.paymentanalysis.chat;

import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient.ChatMessage;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient.LlmResultMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Turns verified missing-input facts into a helpful follow-up question.
 *
 * <p>The caller owns all validation and candidate selection. The model only
 * explains those facts in natural language; it is never allowed to invent a
 * metric, date, field identifier, or a missing requirement.</p>
 */
@Component
public class ClarificationPlanner {

    private static final int MAX_CANDIDATES = 6;

    private final OpenAiCompatibleLlmClient llmClient;
    private final ObjectMapper objectMapper;

    public ClarificationPlanner(OpenAiCompatibleLlmClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    public static ClarificationPlanner noOp() {
        return new ClarificationPlanner(null, new ObjectMapper());
    }

    public String plan(ClarificationRequest request, String model) {
        ClarificationRequest facts = request == null ? ClarificationRequest.empty() : request.normalized();
        if (llmClient == null || llmClient.isMockEnabled()) {
            return fallback(facts);
        }
        List<ChatMessage> messages = List.of(
                new ChatMessage("system", """
                        你是支付数据助手的澄清交互规划器。请根据 verifiedFacts 写一段简洁、自然的中文追问。
                        你的工作只是帮助用户补齐信息，不能修改查询或归因模板，也不能声称已经执行分析。
                        严格遵守：
                        1. 只能询问 missingItems 中的缺口；不能新增其他必填项。
                        2. 不得编造字段 ID、指标、日期、周期、筛选值或业务结论。
                        3. candidateExamples 仅是可选示例；没有候选时不要杜撰选择项。
                        4. 先用一句话说明你理解到的用户意图，再把相关缺口合并成 1 到 3 个可直接回答的问题。
                        5. 不要使用“继续调整模板还是解释结果”这类泛泛的二选一问题，除非 missingItems 明确要求确认本轮目的。
                        6. 不要暴露内部实现、RAG、字段代码或 JSON。
                        只返回 JSON：{"reply":"..."}。
                        """),
                new ChatMessage("user", payload(facts)));
        try {
            LlmResultMessage result = llmClient.completeWithMessage(
                    messages, "{\"reply\":\"\"}", model);
            String reply = parseReply(result.content());
            return StringUtils.hasText(reply) ? reply : fallback(facts);
        } catch (RuntimeException exception) {
            return fallback(facts);
        }
    }

    private String payload(ClarificationRequest facts) {
        try {
            return objectMapper.writeValueAsString(facts);
        } catch (Exception exception) {
            return "用户本轮输入：" + facts.userMessage()
                    + "\n仍需确认：" + facts.missingItems().stream()
                            .map(MissingItem::label).reduce((left, right) -> left + "、" + right).orElse("必要信息");
        }
    }

    private String parseReply(String content) {
        try {
            JsonNode root = objectMapper.readTree(stripFence(content));
            return root.path("reply").asText("").trim();
        } catch (Exception exception) {
            return "";
        }
    }

    private String fallback(ClarificationRequest facts) {
        String missing = facts.missingItems().stream().map(MissingItem::label)
                .reduce((left, right) -> left + "、" + right).orElse("必要信息");
        String examples = facts.candidateExamples().isEmpty() ? "" : " 可参考："
                + String.join("、", facts.candidateExamples()) + "。";
        return "我理解你想继续处理这项数据诉求。还需要确认：" + missing + "。"
                + examples + "请直接补充即可。";
    }

    private static String stripFence(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        if (trimmed.startsWith("```")) {
            int firstBreak = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstBreak >= 0 && lastFence > firstBreak) {
                return trimmed.substring(firstBreak + 1, lastFence).trim();
            }
        }
        return trimmed;
    }

    public record MissingItem(String key, String label) {
        public MissingItem {
            key = key == null ? "" : key.trim();
            label = label == null ? "" : label.trim();
        }
    }

    public record ClarificationRequest(
            String scenario,
            String userMessage,
            List<MissingItem> missingItems,
            List<String> knownFacts,
            List<String> candidateExamples) {

        public ClarificationRequest {
            scenario = scenario == null ? "" : scenario.trim();
            userMessage = userMessage == null ? "" : userMessage.trim();
            missingItems = missingItems == null ? List.of() : List.copyOf(missingItems);
            knownFacts = knownFacts == null ? List.of() : List.copyOf(knownFacts);
            candidateExamples = candidateExamples == null ? List.of() : List.copyOf(candidateExamples);
        }

        static ClarificationRequest empty() {
            return new ClarificationRequest("", "", List.of(), List.of(), List.of());
        }

        ClarificationRequest normalized() {
            List<MissingItem> missing = missingItems.stream()
                    .filter(item -> item != null && StringUtils.hasText(item.label())).toList();
            List<String> known = knownFacts.stream().filter(StringUtils::hasText).map(String::trim).toList();
            List<String> candidates = new ArrayList<>();
            for (String candidate : candidateExamples) {
                if (StringUtils.hasText(candidate) && candidates.size() < MAX_CANDIDATES) {
                    candidates.add(candidate.trim());
                }
            }
            return new ClarificationRequest(scenario, userMessage, missing, known, List.copyOf(candidates));
        }
    }
}
