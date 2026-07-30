package com.company.paymentanalysis.analysis;

import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient.ChatMessage;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient.LlmResultMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class IntentRecognitionService {

    private final OpenAiCompatibleLlmClient llmClient;
    private final ObjectMapper objectMapper;

    public IntentRecognitionService(
            OpenAiCompatibleLlmClient llmClient,
            ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper.copy()
                .findAndRegisterModules()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public RecognitionResponse recognize(String question, AnalysisContext context) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("用户问题不能为空");
        }
        AnalysisContext safeContext = context == null
                ? new AnalysisContext(null, "", List.of(), List.of())
                : context;
        try {
            LlmResultMessage message = llmClient.completeWithMessage(
                    List.of(
                            new ChatMessage("system", systemPrompt(safeContext)),
                            new ChatMessage(
                                    "user",
                                    "AnalysisContext:\n"
                                            + objectMapper.writeValueAsString(safeContext)
                                            + "\n用户问题："
                                            + question)),
                    mockResult(question, safeContext));
            IntentRecognitionResult result = parseAndValidate(message.content());
            return new RecognitionResponse(result, message);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("意图识别结果不是合法的严格 JSON", exception);
        }
    }

    public String engineLabel() {
        return llmClient.modelLabel();
    }

    private IntentRecognitionResult parseAndValidate(String content) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(stripMarkdownFence(content));
        JsonNode payload = root != null && root.path("answer").isObject()
                ? root.path("answer")
                : root;
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("意图识别结果必须是 JSON 对象");
        }
        for (String required : List.of(
                "intent",
                "confidence",
                "metricText",
                "dimensionTexts",
                "filters",
                "comparisonSubjects",
                "requestedCalculations",
                "topN",
                "needsDataQuery",
                "needsKnowledgeBase",
                "missingSlots",
                "clarificationQuestion")) {
            if (!payload.has(required)) {
                throw new IllegalArgumentException("意图识别结果缺少字段：" + required);
            }
        }
        IntentRecognitionResult result =
                objectMapper.treeToValue(payload, IntentRecognitionResult.class);
        if (result == null || result.intent() == null) {
            throw new IllegalArgumentException("意图识别结果缺少 intent");
        }
        if (Double.isNaN(result.confidence())
                || result.confidence() < 0
                || result.confidence() > 1) {
            throw new IllegalArgumentException("confidence 必须在 0 到 1 之间");
        }
        if (result.topN() != null && result.topN() < 0) {
            throw new IllegalArgumentException("topN 必须为正整数");
        }
        if (result.topN() != null && result.topN() == 0) {
            if (result.intent() == IntentType.RANK_QUERY) {
                throw new IllegalArgumentException("RANK_QUERY 的 topN 必须为正整数");
            }
            result = new IntentRecognitionResult(
                    result.intent(),
                    result.confidence(),
                    result.metricText(),
                    result.dimensionTexts(),
                    result.filters(),
                    result.comparisonSubjects(),
                    result.requestedCalculations(),
                    null,
                    result.needsDataQuery(),
                    result.needsKnowledgeBase(),
                    result.missingSlots(),
                    result.clarificationQuestion());
        }
        validateFilters(result.filters(), "filters");
        for (ComparisonSubject subject : result.comparisonSubjects()) {
            if (subject == null || subject.label() == null || subject.label().isBlank()) {
                throw new IllegalArgumentException("comparisonSubjects 中的 label 不能为空");
            }
            validateFilters(subject.filters(), "comparisonSubjects.filters");
        }
        return result;
    }

    private void validateFilters(List<FilterCondition> filters, String fieldName) {
        for (FilterCondition filter : filters) {
            if (filter == null
                    || filter.field() == null
                    || filter.field().isBlank()
                    || filter.operator() == null
                    || filter.operator().isBlank()
                    || filter.values().isEmpty()) {
                throw new IllegalArgumentException(fieldName + " 中存在不完整过滤条件");
            }
        }
    }

    private String stripMarkdownFence(String content) {
        if (content == null) {
            return "";
        }
        String trimmed = content.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstNewline = trimmed.indexOf('\n');
        int lastFence = trimmed.lastIndexOf("```");
        if (firstNewline < 0 || lastFence <= firstNewline) {
            return trimmed;
        }
        return trimmed.substring(firstNewline + 1, lastFence).trim();
    }

    private String systemPrompt(AnalysisContext context) {
        return """
                你是支付数据分析意图识别器。只返回一个严格 JSON 对象，不要 Markdown，不要解释。
                JSON 必须完整包含 intent、confidence、metricText、dimensionTexts、filters、
                comparisonSubjects、requestedCalculations、topN、needsDataQuery、
                needsKnowledgeBase、missingSlots、clarificationQuestion。
                intent 只能使用约定的 IntentType 枚举值。
                filters 格式为 [{"field":"...","operator":"...","values":["..."]}]。
                comparisonSubjects 格式为
                [{"label":"...","filters":[{"field":"...","operator":"...","values":["..."]}]}]。
                COMPARE_QUERY 必须保留恰好两个对象，禁止合并或覆盖。
                “A比B少多少”返回 A_LESS_THAN_B、ABSOLUTE_DIFFERENCE；
                “A比B多多少”返回 A_MORE_THAN_B、ABSOLUTE_DIFFERENCE；
                用户要求百分比或增长率时再增加 CHANGE_RATE。
                TREND_QUERY 必须返回时间范围和时间维度，requestedCalculations 使用 DAY、MONTH 或 YEAR。
                RANK_QUERY 必须返回指标、至少一个分组维度、topN，并使用 ASC 或 DESC。
                所有绝对日期使用 yyyy-MM-dd，月份范围使用 BETWEEN 覆盖完整月份。
                当前日期是 %s。结合 AnalysisContext 恢复省略条件，但不要虚构条件。
                缺少必要槽位时返回 CLARIFICATION、needsDataQuery=false，并填写追问。
                只识别意图和槽位，不计算差额、比例、趋势或排名。
                """.formatted(context.currentDate());
    }

    @SuppressWarnings("unused")
    private String legacySystemPrompt(AnalysisContext context) {
        return """
                你是支付数据分析意图识别器。只返回一个 JSON 对象，不要 Markdown，不要解释。
                JSON 必须完整包含：
                intent, confidence, metricText, dimensionTexts, filters, comparisonSubjects,
                requestedCalculations, topN, needsDataQuery, needsKnowledgeBase,
                missingSlots, clarificationQuestion。
                intent 只能是 SINGLE_QUERY、GROUP_QUERY、COMPARE_QUERY、TREND_QUERY、
                RANK_QUERY、METRIC_EXPLAIN、QUERY_WITH_EXPLANATION、DIAGNOSIS_QUERY、
                CLARIFICATION、OUT_OF_SCOPE。
                filters 格式为 [{"field":"...","operator":"...","values":["..."]}]。
                comparisonSubjects 格式为
                [{"label":"...","filters":[{"field":"...","operator":"...","values":["..."]}]}]。
                SINGLE_QUERY 是单指标汇总查询；GROUP_QUERY 是按一个或多个维度分组；
                COMPARE_QUERY 是两个明确对象之间的比较。
                COMPARE_QUERY 必须把两个对象分别放入 comparisonSubjects，数量必须是 2。
                例如“6月比7月少多少”必须分别保留 6月和 7月的时间过滤，绝不能合并或覆盖。
                “英国比法国”必须分别保留英国和法国的收单地区过滤。
                绝对日期使用 yyyy-MM-dd。月份过滤用 BETWEEN，覆盖该月完整日期范围。
                当前日期是 %s。结合 AnalysisContext 恢复省略的多轮条件，但不要虚构用户未提供且
                AnalysisContext 也没有的指标或时间。缺少必要槽位时返回 CLARIFICATION，
                needsDataQuery=false，并填写 missingSlots 和 clarificationQuestion。
                metricText 和 dimensionTexts 保存业务名称；不要输出 SmartBI 字段名。
                """.formatted(context.currentDate());
    }

    private String mockResult(String question, AnalysisContext context) throws JsonProcessingException {
        if (question.contains("\u8d70\u52bf")
                || question.contains("\u8d8b\u52bf")
                || question.contains("\u53d8\u5316")) {
            return mockTrendResult(context);
        }
        if (question.contains("\u6700\u9ad8")
                || question.contains("\u6700\u4f4e")
                || question.contains("\u524d")
                || question.contains("\u540e")) {
            return mockRankResult(question, context);
        }
        String metric = question.contains("人民币") ? "人民币总金额"
                : question.contains("金额") ? "transactionAmount"
                : question.contains("笔数") ? "transactionCount"
                : question.contains("成功率") ? "successRate"
                : "";
        List<String> dimensions = new java.util.ArrayList<>();
        if (question.contains("受理渠道") || question.contains("渠道")) {
            dimensions.add("channel");
        }
        if (question.contains("地区")) {
            dimensions.add("region");
        }
        if (question.contains("商户")) {
            dimensions.add("merchantType");
        }
        if (question.contains("每个月") || question.contains("按月")) {
            dimensions.add("tradeMonth");
        }
        List<FilterCondition> filters = new java.util.ArrayList<>();
        if (question.contains("近三个月")) {
            filters.add(new FilterCondition(
                    "tradeDate", "BETWEEN",
                    List.of(context.currentDate().minusMonths(2).withDayOfMonth(1).toString(),
                            context.currentDate().toString())));
        } else if (question.contains("最近7天") || question.contains("近7天")) {
            filters.add(new FilterCondition(
                    "tradeDate", "BETWEEN",
                    List.of(context.currentDate().minusDays(6).toString(), context.currentDate().toString())));
        } else if (question.contains("6月") && !question.contains("7月")) {
            filters.add(new FilterCondition(
                    "tradeDate", "BETWEEN", List.of("2026-06-01", "2026-06-30")));
        } else if (question.contains("7月") || question.contains("本月")) {
            filters.add(new FilterCondition(
                    "tradeDate", "BETWEEN", List.of("2026-07-01", context.currentDate().toString())));
        }
        boolean compareMonths = question.contains("6月") && question.contains("7月");
        boolean compareCountries = question.contains("英国") && question.contains("法国");
        List<ComparisonSubject> subjects = compareMonths
                ? List.of(
                        new ComparisonSubject("6月", List.of(new FilterCondition(
                                "tradeDate", "BETWEEN", List.of("2026-06-01", "2026-06-30")))),
                        new ComparisonSubject("7月", List.of(new FilterCondition(
                                "tradeDate", "BETWEEN", List.of("2026-07-01", "2026-07-31")))))
                : compareCountries
                        ? List.of(
                                new ComparisonSubject("英国", List.of(new FilterCondition(
                                        "acquiringRegion", "EQUALS", List.of("英国")))),
                                new ComparisonSubject("法国", List.of(new FilterCondition(
                                        "acquiringRegion", "EQUALS", List.of("法国")))))
                        : List.of();
        IntentType intent = compareMonths || compareCountries
                ? IntentType.COMPARE_QUERY
                : dimensions.isEmpty() ? IntentType.SINGLE_QUERY : IntentType.GROUP_QUERY;
        if (List.of("帮我写一首诗", "hello", "你好").contains(question.trim())) {
            intent = IntentType.OUT_OF_SCOPE;
        }
        IntentRecognitionResult result = new IntentRecognitionResult(
                intent,
                1,
                metric,
                dimensions,
                filters,
                subjects,
                compareMonths
                        ? List.of("A_LESS_THAN_B", "ABSOLUTE_DIFFERENCE")
                        : compareCountries
                                ? List.of("A_MORE_THAN_B", "ABSOLUTE_DIFFERENCE")
                                : List.of(),
                null,
                intent != IntentType.OUT_OF_SCOPE,
                false,
                List.of(),
                "");
        return objectMapper.writeValueAsString(result);
    }

    private String mockTrendResult(AnalysisContext context)
            throws JsonProcessingException {
        LocalDate end = context.currentDate();
        LocalDate start = end.minusMonths(5).withDayOfMonth(1);
        return objectMapper.writeValueAsString(new IntentRecognitionResult(
                IntentType.TREND_QUERY,
                1,
                "transactionAmount",
                List.of("tradeMonth"),
                List.of(new FilterCondition(
                        "tradeDate", "BETWEEN", List.of(start.toString(), end.toString()))),
                List.of(),
                List.of("MONTH"),
                null,
                true,
                false,
                List.of(),
                ""));
    }

    private String mockRankResult(String question, AnalysisContext context)
            throws JsonProcessingException {
        boolean ascending = question.contains("\u6700\u4f4e")
                || question.contains("\u6700\u5c11")
                || question.contains("\u540e");
        int topN = question.contains("3") ? 3 : question.contains("5") ? 5 : 1;
        LocalDate start = context.currentDate().withDayOfMonth(1);
        return objectMapper.writeValueAsString(new IntentRecognitionResult(
                IntentType.RANK_QUERY,
                1,
                question.contains("\u7b14\u6570") ? "transactionCount" : "transactionAmount",
                List.of("acquiringRegion"),
                List.of(new FilterCondition(
                        "tradeDate",
                        "BETWEEN",
                        List.of(start.toString(), context.currentDate().toString()))),
                List.of(),
                List.of(ascending ? "ASC" : "DESC"),
                topN,
                true,
                false,
                List.of(),
                ""));
    }

    public record RecognitionResponse(
            IntentRecognitionResult result,
            LlmResultMessage llmMessage) {
    }
}
