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
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ChatQueryInterpreter {

    private static final Set<String> PERIOD_ACTIONS = Set.of("KEEP", "SET", "CLEAR");
    private static final Set<String> LIST_ACTIONS = Set.of("KEEP", "ADD", "REMOVE", "CLEAR");
    private static final Set<String> FILTER_ACTIONS = Set.of("KEEP", "SET", "REMOVE", "CLEAR");
    private static final Set<String> FILTER_OPERATORS = Set.of("EQUALS", "IN");
    private static final Set<String> SORT_ACTIONS = Set.of("KEEP", "SET", "CLEAR");
    private static final Set<String> SORT_DIRECTIONS = Set.of("ASC", "DESC");
    private static final Set<String> METRICS =
            Set.of("transactionAmount", "transactionCount", "successRate");
    private static final Set<String> DIMENSIONS = Set.of(
            "tradeYear", "tradeMonth", "tradeDate", "channel", "region",
            "merchantType", "paymentMethod");

    private final OpenAiCompatibleLlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ChatQueryInterpreter(
            OpenAiCompatibleLlmClient llmClient, ObjectMapper objectMapper, Clock clock) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public QueryActionResult interpret(ChatRequest request, QueryContext current) {
        try {
            List<ChatMessage> messages = List.of(
                    new ChatMessage("system", systemPrompt()),
                    new ChatMessage("user", userPrompt(request.message(), current)));
            String mockContent = objectMapper.writeValueAsString(QueryAction.keep());
            LlmResultMessage llmMessage = llmClient.completeWithMessage(messages, mockContent);
            QueryAction action = parseAndValidate(llmMessage.content());
            return new QueryActionResult(action, llmMessage);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("大模型返回的 QueryAction JSON 无法解析", exception);
        }
    }

    public String engineLabel() {
        return llmClient.modelLabel();
    }

    private String systemPrompt() {
        return """
                你是支付数据查数条件转换器。你的唯一任务是把本轮用户输入转换成 QueryAction JSON。
                只返回一个严格 JSON 对象，不要 Markdown、解释、候选答案或额外字段。
                不要使用 answer、QueryAction 等外层包装。顶层对象必须严格采用以下结构：
                {
                  "intent":"QUERY",
                  "periodAction":"KEEP",
                  "startDate":"",
                  "endDate":"",
                  "periodLabel":"",
                  "metricAction":{"operations":[{"action":"KEEP","ids":[]}]},
                  "dimensionAction":{"operations":[{"action":"KEEP","ids":[]}]},
                  "filterAction":{"operations":[{"action":"KEEP","dimensionId":"","operator":"","values":[]}]},
                  "sortAction":{"action":"KEEP","items":[]}
                }
                intent 固定返回 QUERY。
                periodAction 只能是 KEEP、SET、CLEAR。
                periodAction=SET 时必须返回 yyyy-MM-dd 格式的 startDate、endDate 和 periodLabel；
                KEEP 或 CLEAR 时 startDate、endDate、periodLabel 必须为空字符串。
                metricAction 和 dimensionAction 的格式固定为：
                {"operations":[{"action":"ADD","ids":["..."]}]}
                action 只能是 KEEP、ADD、REMOVE、CLEAR。
                KEEP 或 CLEAR 必须单独出现且 ids 为空；ADD 与 REMOVE 可以同时出现。
                用户要求替换某类条件时，必须同时返回 REMOVE 原有 id 和 ADD 新 id；
                不得因为已经生成 REMOVE 而遗漏用户明确要求的新 id。
                dimensionAction 表示分组维度，ADD 某维度就是增加对应的 GROUP BY。
                filterAction.operations 的 action 只能是 KEEP、SET、REMOVE、CLEAR：
                SET 必须填写 dimensionId、operator 和 values；operator 只能是 EQUALS 或 IN；
                REMOVE 只填写 dimensionId；KEEP/CLEAR 必须单独出现并将其他字段置空。
                sortAction.action 只能是 KEEP、SET、CLEAR。SET 时 items 至少一项，
                每项格式为 {"fieldId":"...","direction":"ASC"}，direction 只能是 ASC 或 DESC；
                items 的先后顺序就是多字段排序优先级。
                metric ids 只能是 transactionAmount、transactionCount、successRate。
                dimension ids 只能是 tradeYear、tradeMonth、tradeDate、channel、region、
                merchantType、paymentMethod。
                当前上下文代表已经生效的查询条件。本轮没有修改某类条件时必须返回 KEEP，
                不要重复 ADD 已存在的条件。相对日期必须根据用户消息中提供的当前日期换算。
                时间范围和时间分组维度是两件事；除非用户明确要求按年、月、日分组，
                否则不要自动增加 tradeYear、tradeMonth 或 tradeDate。
                """;
    }

    private String userPrompt(String message, QueryContext current) throws JsonProcessingException {
        return "当前日期：" + LocalDate.now(clock)
                + "\n当前查询状态：" + objectMapper.writeValueAsString(current)
                + "\n用户本轮输入：" + message;
    }

    private QueryAction parseAndValidate(String content) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(stripMarkdownFence(content));
        JsonNode payload = unwrapTransportEnvelope(root);
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("QueryAction 必须是 JSON 对象");
        }
        for (String field : List.of(
                "intent", "periodAction", "startDate", "endDate", "periodLabel",
                "metricAction", "dimensionAction", "filterAction", "sortAction")) {
            if (!payload.has(field)) {
                throw new IllegalArgumentException("QueryAction 缺少字段：" + field);
            }
        }
        validateExactFields(payload, Set.of(
                "intent", "periodAction", "startDate", "endDate", "periodLabel",
                "metricAction", "dimensionAction", "filterAction", "sortAction"), "QueryAction");
        validateActionJson(payload.path("metricAction"), "metricAction");
        validateActionJson(payload.path("dimensionAction"), "dimensionAction");
        validateFilterActionJson(payload.path("filterAction"));
        validateSortActionJson(payload.path("sortAction"));
        QueryAction action = objectMapper.treeToValue(payload, QueryAction.class);
        if (!"QUERY".equals(action.intent())) {
            throw new IllegalArgumentException("QueryAction.intent 只能是 QUERY");
        }
        if (!PERIOD_ACTIONS.contains(action.periodAction())) {
            throw new IllegalArgumentException("不支持的 periodAction：" + action.periodAction());
        }
        validatePeriod(action);
        return new QueryAction(
                "QUERY",
                action.periodAction(),
                action.startDate(),
                action.endDate(),
                action.periodLabel(),
                validateActionPlan(action.metricAction(), METRICS, 3, "metricAction"),
                validateActionPlan(action.dimensionAction(), DIMENSIONS, 7, "dimensionAction"),
                validateFilterAction(action.filterAction()),
                validateSortAction(action.sortAction()));
    }

    private JsonNode unwrapTransportEnvelope(JsonNode root) {
        if (root != null && root.path("answer").isObject()) {
            return root.path("answer");
        }
        if (root != null && root.path("QueryAction").isObject()) {
            return root.path("QueryAction");
        }
        return root;
    }

    private void validatePeriod(QueryAction action) {
        if ("SET".equals(action.periodAction())) {
            if (action.startDate().isBlank() || action.endDate().isBlank() || action.periodLabel().isBlank()) {
                throw new IllegalArgumentException("periodAction=SET 时日期和标签不能为空");
            }
            LocalDate start = LocalDate.parse(action.startDate());
            LocalDate end = LocalDate.parse(action.endDate());
            if (start.isAfter(end)) {
                throw new IllegalArgumentException("QueryAction 开始日期不能晚于结束日期");
            }
            return;
        }
        if (!action.startDate().isBlank() || !action.endDate().isBlank() || !action.periodLabel().isBlank()) {
            throw new IllegalArgumentException("periodAction=KEEP/CLEAR 时日期和标签必须为空");
        }
    }

    private ActionPlan validateActionPlan(
            ActionPlan plan, Set<String> allowedIds, int maximum, String fieldName) {
        if (plan == null || plan.operations() == null || plan.operations().isEmpty()) {
            throw new IllegalArgumentException(fieldName + ".operations 不能为空");
        }
        List<ActionOperation> normalized = new ArrayList<>();
        Set<String> seenActions = new LinkedHashSet<>();
        for (ActionOperation operation : plan.operations()) {
            if (operation == null || !LIST_ACTIONS.contains(operation.action())) {
                throw new IllegalArgumentException(fieldName + " 包含不支持的 action");
            }
            if (!seenActions.add(operation.action())) {
                throw new IllegalArgumentException(fieldName + " 中同一种 action 不能重复");
            }
            List<String> ids = operation.ids() == null ? List.of() : List.copyOf(operation.ids());
            List<String> unknownIds = ids.stream().filter(id -> !allowedIds.contains(id)).toList();
            if (!unknownIds.isEmpty()) {
                throw new IllegalArgumentException(fieldName + " 包含不支持的 id：" + unknownIds);
            }
            if (new LinkedHashSet<>(ids).size() != ids.size()) {
                throw new IllegalArgumentException(fieldName + " 中的 id 不允许重复");
            }
            if (ids.size() > maximum) {
                throw new IllegalArgumentException(fieldName + " 最多包含 " + maximum + " 个 id");
            }
            if (("KEEP".equals(operation.action()) || "CLEAR".equals(operation.action()))
                    && (plan.operations().size() != 1 || !ids.isEmpty())) {
                throw new IllegalArgumentException(fieldName + " 的 KEEP/CLEAR 必须单独出现且 ids 为空");
            }
            if (("ADD".equals(operation.action()) || "REMOVE".equals(operation.action())) && ids.isEmpty()) {
                throw new IllegalArgumentException(fieldName + " 的 ADD/REMOVE 必须包含合法 ids");
            }
            normalized.add(new ActionOperation(operation.action(), ids));
        }
        return new ActionPlan(List.copyOf(normalized));
    }

    private FilterAction validateFilterAction(FilterAction action) {
        if (action == null || action.operations() == null || action.operations().isEmpty()) {
            throw new IllegalArgumentException("filterAction.operations 不能为空");
        }
        List<FilterOperation> operations = action.operations();
        for (FilterOperation operation : operations) {
            if (operation == null || !FILTER_ACTIONS.contains(operation.action())) {
                throw new IllegalArgumentException("filterAction 包含不支持的 action");
            }
            boolean singleton = "KEEP".equals(operation.action()) || "CLEAR".equals(operation.action());
            if (singleton && (operations.size() != 1
                    || !operation.dimensionId().isBlank()
                    || !operation.operator().isBlank()
                    || !operation.values().isEmpty())) {
                throw new IllegalArgumentException("filterAction 的 KEEP/CLEAR 必须单独出现且条件为空");
            }
            if ("REMOVE".equals(operation.action())
                    && (!DIMENSIONS.contains(operation.dimensionId())
                    || !operation.operator().isBlank()
                    || !operation.values().isEmpty())) {
                throw new IllegalArgumentException("filterAction 的 REMOVE 只允许填写合法 dimensionId");
            }
            if ("SET".equals(operation.action())
                    && (!DIMENSIONS.contains(operation.dimensionId())
                    || !FILTER_OPERATORS.contains(operation.operator())
                    || operation.values().isEmpty()
                    || operation.values().stream().anyMatch(String::isBlank))) {
                throw new IllegalArgumentException("filterAction 的 SET 条件不合法");
            }
            if ("SET".equals(operation.action())
                    && (new LinkedHashSet<>(operation.values()).size() != operation.values().size()
                    || "EQUALS".equals(operation.operator()) && operation.values().size() != 1)) {
                throw new IllegalArgumentException("filterAction 的 SET 值数量或重复项不合法");
            }
        }
        long dimensions = operations.stream()
                .filter(operation -> "SET".equals(operation.action()) || "REMOVE".equals(operation.action()))
                .map(FilterOperation::dimensionId).distinct().count();
        if (dimensions != operations.stream()
                .filter(operation -> "SET".equals(operation.action()) || "REMOVE".equals(operation.action()))
                .count()) {
            throw new IllegalArgumentException("filterAction 同一维度每轮只能操作一次");
        }
        return new FilterAction(List.copyOf(operations));
    }

    private SortAction validateSortAction(SortAction action) {
        if (action == null || !SORT_ACTIONS.contains(action.action())) {
            throw new IllegalArgumentException("sortAction.action 不合法");
        }
        List<SortItem> items = action.items() == null ? List.of() : List.copyOf(action.items());
        if (!"SET".equals(action.action()) && !items.isEmpty()) {
            throw new IllegalArgumentException("sortAction KEEP/CLEAR 时 items 必须为空");
        }
        if ("SET".equals(action.action()) && items.isEmpty()) {
            throw new IllegalArgumentException("sortAction SET 时 items 不能为空");
        }
        Set<String> allowedFields = new LinkedHashSet<>(METRICS);
        allowedFields.addAll(DIMENSIONS);
        Set<String> seen = new LinkedHashSet<>();
        for (SortItem item : items) {
            if (item == null || !allowedFields.contains(item.fieldId())
                    || !SORT_DIRECTIONS.contains(item.direction())) {
                throw new IllegalArgumentException("sortAction 包含不合法的排序项");
            }
            if (!seen.add(item.fieldId())) {
                throw new IllegalArgumentException("sortAction 排序字段不允许重复");
            }
        }
        return new SortAction(action.action(), items);
    }

    private void validateActionJson(JsonNode plan, String fieldName) {
        if (!plan.isObject()) {
            throw new IllegalArgumentException(fieldName + " 必须是 JSON 对象");
        }
        validateExactFields(plan, Set.of("operations"), fieldName);
        JsonNode operations = plan.path("operations");
        if (!operations.isArray()) {
            throw new IllegalArgumentException(fieldName + ".operations 必须是数组");
        }
        for (JsonNode operation : operations) {
            if (!operation.isObject()) {
                throw new IllegalArgumentException(fieldName + ".operations 元素必须是对象");
            }
            validateExactFields(operation, Set.of("action", "ids"), fieldName + ".operations");
        }
    }

    private void validateFilterActionJson(JsonNode action) {
        if (!action.isObject()) {
            throw new IllegalArgumentException("filterAction 必须是 JSON 对象");
        }
        validateExactFields(action, Set.of("operations"), "filterAction");
        JsonNode operations = action.path("operations");
        if (!operations.isArray()) {
            throw new IllegalArgumentException("filterAction.operations 必须是数组");
        }
        for (JsonNode operation : operations) {
            validateExactFields(operation, Set.of(
                    "action", "dimensionId", "operator", "values"), "filterAction.operations");
        }
    }

    private void validateSortActionJson(JsonNode action) {
        if (!action.isObject()) {
            throw new IllegalArgumentException("sortAction 必须是 JSON 对象");
        }
        validateExactFields(action, Set.of("action", "items"), "sortAction");
        JsonNode items = action.path("items");
        if (!items.isArray()) {
            throw new IllegalArgumentException("sortAction.items 必须是数组");
        }
        for (JsonNode item : items) {
            validateExactFields(item, Set.of("fieldId", "direction"), "sortAction.items");
        }
    }

    private void validateExactFields(JsonNode node, Set<String> expected, String fieldName) {
        Set<String> actual = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            Set<String> extra = new LinkedHashSet<>(actual);
            extra.removeAll(expected);
            if (!extra.isEmpty()) {
                throw new IllegalArgumentException(fieldName + " 包含额外字段：" + extra);
            }
        }
    }

    private String stripMarkdownFence(String content) {
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

    public record QueryAction(
            String intent,
            String periodAction,
            String startDate,
            String endDate,
            String periodLabel,
            ActionPlan metricAction,
            ActionPlan dimensionAction,
            FilterAction filterAction,
            SortAction sortAction) implements Serializable {

        public QueryAction {
            intent = intent == null ? "" : intent;
            periodAction = periodAction == null ? "" : periodAction;
            startDate = startDate == null ? "" : startDate;
            endDate = endDate == null ? "" : endDate;
            periodLabel = periodLabel == null ? "" : periodLabel;
        }

        public static QueryAction keep() {
            ActionPlan keep = new ActionPlan(List.of(new ActionOperation("KEEP", List.of())));
            FilterAction keepFilters = new FilterAction(List.of(
                    new FilterOperation("KEEP", "", "", List.of())));
            return new QueryAction(
                    "QUERY", "KEEP", "", "", "", keep, keep,
                    keepFilters, new SortAction("KEEP", List.of()));
        }
    }

    public record ActionPlan(List<ActionOperation> operations) implements Serializable {
    }

    public record ActionOperation(String action, List<String> ids) implements Serializable {
    }

    public record FilterAction(List<FilterOperation> operations) implements Serializable {
    }

    public record FilterOperation(
            String action, String dimensionId, String operator, List<String> values)
            implements Serializable {

        public FilterOperation {
            action = action == null ? "" : action;
            dimensionId = dimensionId == null ? "" : dimensionId;
            operator = operator == null ? "" : operator;
            values = values == null ? List.of() : List.copyOf(values);
        }
    }

    public record SortAction(String action, List<SortItem> items) implements Serializable {
    }

    public record SortItem(String fieldId, String direction) implements Serializable {
    }

    public record QueryActionResult(QueryAction action, LlmResultMessage llmMessage) {
    }
}
