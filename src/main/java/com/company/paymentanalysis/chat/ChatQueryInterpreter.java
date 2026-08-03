package com.company.paymentanalysis.chat;

import com.company.paymentanalysis.controller.ChatQueryController.ChatRequest;
import com.company.paymentanalysis.controller.ChatQueryController.QueryContext;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient.ChatMessage;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient.LlmResultMessage;
import com.company.paymentanalysis.query.QueryMetadataCatalog;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.Serializable;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ChatQueryInterpreter {

    private static final Set<String> LIST_ACTIONS = Set.of("SET", "CLEAR");
    private static final Set<String> FILTER_ACTIONS = Set.of("SET", "CLEAR");
    private static final Set<String> FILTER_OPERATORS = Set.of("EQUALS", "IN", "BETWEEN");
    private static final Set<String> SORT_ACTIONS = Set.of("SET", "CLEAR");
    private static final Set<String> SORT_DIRECTIONS = Set.of("ASC", "DESC");
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
            ObjectNode mockPayload = objectMapper.valueToTree(QueryAction.fromContext(current));
            mockPayload.put("explanation", "沿用当前查询状态，未增加额外查询条件。");
            String mockContent = objectMapper.writeValueAsString(mockPayload);
            LlmResultMessage firstMessage;
            try {
                firstMessage = complete(messages, mockContent, request.model());
            } catch (RuntimeException exception) {
                throw interpretationFailure(
                        "LLM_REQUEST", "大模型调用失败", request, messages, exception);
            }
            try {
                ParsedQueryAction parsed = parseAndValidate(firstMessage.content());
                return new QueryActionResult(parsed.action(), parsed.explanation(), firstMessage);
            } catch (JsonProcessingException | RuntimeException firstValidationError) {
                List<ChatMessage> repairMessages = new ArrayList<>(messages);
                repairMessages.add(new ChatMessage("assistant", firstMessage.content()));
                repairMessages.add(new ChatMessage(
                        "user",
                        "上一个 QueryAction 未通过协议校验："
                                + firstValidationError.getMessage()
                                + "。请根据原始用户输入修正，只返回完整 QueryAction JSON；"
                                + "不要添加原始输入中不存在的查询条件。"));
                LlmResultMessage repairedMessage;
                try {
                    repairedMessage = complete(repairMessages, mockContent, request.model());
                } catch (RuntimeException exception) {
                    throw new QueryInterpretationException(
                            "LLM_REPAIR_REQUEST",
                            "协议修复请求失败：" + conciseMessage(exception),
                            firstMessage,
                            exception);
                }
                try {
                    ParsedQueryAction parsed = parseAndValidate(repairedMessage.content());
                    return new QueryActionResult(
                            parsed.action(),
                            parsed.explanation(),
                            repairedMessage,
                            List.of("首次模型输出未通过协议校验，已要求模型按原协议重新生成"));
                } catch (JsonProcessingException | RuntimeException secondValidationError) {
                    String completed;
                    try {
                        completed = completeMissingSortAction(repairedMessage.content(), current);
                    } catch (JsonProcessingException normalizationError) {
                        throw protocolFailure(repairedMessage, normalizationError);
                    }
                    if (!completed.equals(repairedMessage.content())) {
                        try {
                            ParsedQueryAction parsed = parseAndValidate(completed);
                            return new QueryActionResult(
                                    parsed.action(),
                                    parsed.explanation(),
                                    repairedMessage,
                                    List.of(
                                            "模型两次输出的排序结构不完整，已保留本轮之前的排序状态",
                                            "原始校验原因：" + secondValidationError.getMessage()));
                        } catch (JsonProcessingException | RuntimeException finalValidationError) {
                            throw protocolFailure(repairedMessage, finalValidationError);
                        }
                    }
                    throw protocolFailure(repairedMessage, secondValidationError);
                }
            }
        } catch (QueryInterpretationException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            throw new QueryInterpretationException(
                    "PROTOCOL_VALIDATION",
                    "大模型返回的 QueryAction JSON 无法解析：" + conciseMessage(exception),
                    null,
                    exception);
        }
    }

    private QueryInterpretationException interpretationFailure(
            String stage, String summary, ChatRequest request,
            List<ChatMessage> requestMessages, RuntimeException cause) {
        LlmResultMessage attempt = new LlmResultMessage(
                engineLabel(request.model()),
                "error",
                "未收到可解析的模型响应：" + conciseMessage(cause),
                List.copyOf(requestMessages));
        return new QueryInterpretationException(
                stage, summary + "：" + conciseMessage(cause), attempt, cause);
    }

    private QueryInterpretationException protocolFailure(
            LlmResultMessage llmMessage, Exception cause) {
        return new QueryInterpretationException(
                "PROTOCOL_VALIDATION",
                "QueryAction 协议校验失败：" + conciseMessage(cause),
                llmMessage,
                cause);
    }

    private static String conciseMessage(Throwable throwable) {
        String message = throwable == null ? "未知错误" : throwable.getMessage();
        if (!StringUtils.hasText(message)) {
            message = throwable == null ? "未知错误" : throwable.getClass().getSimpleName();
        }
        String normalized = message.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s{2,}", " ").trim();
        return normalized.length() <= 400 ? normalized : normalized.substring(0, 400) + "…";
    }

    public String engineLabel() {
        return llmClient.modelLabel();
    }

    public String engineLabel(String model) {
        return StringUtils.hasText(model) ? llmClient.modelLabel(model) : engineLabel();
    }

    private LlmResultMessage complete(List<ChatMessage> messages, String mockContent, String model) {
        return StringUtils.hasText(model)
                ? llmClient.completeWithMessage(messages, mockContent, model)
                : llmClient.completeWithMessage(messages, mockContent);
    }

    String systemPrompt() {
        return String.join(
                "\n",
                outputContractPrompt(),
                actionStructurePrompt(),
                filterPrompt(),
                temporalFilterPrompt(),
                sortPrompt(),
                stateMergePrompt(),
                finalReviewPrompt(),
                metadataPrompt());
    }

    private String outputContractPrompt() {
        return """
                你是支付数据查数条件转换器。你的唯一任务是结合当前查询状态和本轮用户输入，
                返回本轮处理后的完整目标查询状态 QueryAction JSON。
                只返回一个严格 JSON 对象，不要 Markdown、解释、候选答案或额外字段。
                不要使用 answer、QueryAction 等外层包装。
                顶层字段必须且只能包含：
                intent、metricAction、dimensionAction、filterAction、sortAction、explanation。
                intent 固定返回 QUERY。
                explanation 必须是简短中文说明，只说明本轮输入如何对应到最终的度量、分组、过滤、排序及保留的上下文；
                不要输出思维过程，不要解释系统规则，建议控制在 100 字以内。
                所有 action 只能是 SET 或 CLEAR，不允许返回 KEEP。
                每一类 action 都描述处理本轮输入后的完整最终状态，不是增量操作。""";
    }

    private String actionStructurePrompt() {
        return """
                所有字段统一按动态元数据处理。具有时间语义的维度和其他维度使用同一套 action 协议：
                既可以出现在 dimensionAction 中作为分组维度，也可以出现在 filterAction 中作为过滤条件；
                不要创建独立于维度之外的时间字段或时间范围结构。
                metricAction 和 dimensionAction 的格式固定为：
                {"operations":[{"action":"SET","ids":["..."]}]}
                operations 只能有一个元素。最终集合非空时使用 SET 并返回全部 ids；
                最终集合为空时使用 CLEAR 且 ids 必须为 []。
                dimensionAction 表示完整的 GROUP BY 维度集合。""";
    }

    private String filterPrompt() {
        return """
                最终存在过滤条件时，filterAction.operations 用一个或多个 SET 元素返回完整过滤集合；
                SET 必须填写 dimensionId、operator 和 values，operator 只能是 EQUALS、IN 或 BETWEEN。
                最终没有过滤条件时只返回一个 CLEAR 元素，dimensionId、operator 为空且 values=[]。
                只有用户明确提出成员筛选条件时才修改 filterAction；按维度分组不是过滤。
                不得猜测、补充或枚举用户没有提出的过滤成员。""";
    }

    private String temporalFilterPrompt() {
        return """
                先判断用户表达的是单点、集合还是区间，再选择操作符，不得因为条件具有相对时间语义就默认使用 BETWEEN。
                单个时间点、单个日历日、单个月或单个年份属于单点条件，必须使用对应粒度的时间维度 EQUALS，values 只能包含换算后的一个值。
                任何指向单个相对自然日的表达都属于单点条件。例如设当前日期为 D，则今天为 D、昨天为 D-1 天、前天为 D-2 天；
                这类表达只能产生一个日期值，严禁用 BETWEEN，也严禁把当前日期作为第二个边界。
                多个离散值使用 IN。只有用户明确表达连续时间跨度、起止边界或最近一段时长时才使用 BETWEEN，
                values 必须包含两个有序边界。当前日期只是换算相对时间的基准，不是默认查询边界；
                除非用户表达的区间确实包含当前日期，否则不得把当前日期自动加入 values。
                用户表达“最近 N 个自然日”且未排除当天时，区间必须恰好覆盖 N 个自然日：结束边界为当前日期，
                开始边界为当前日期减去 N-1 天；不得把起止边界都计入后又额外多取一天。""";
    }

    private String sortPrompt() {
        return """
                最终存在排序时 sortAction.action=SET 且 items 至少一项，
                每项格式为 {"fieldId":"...","direction":"ASC"}，direction 只能是 ASC 或 DESC；
                items 的先后顺序就是多字段排序优先级。
                最终没有排序时 sortAction.action=CLEAR 且 items=[]。
                分组维度的出现顺序不代表排序。只有用户本轮明确提出升序、降序、排名、最高、最低等排序语义时才修改排序；
                当前排序为空且用户未表达排序语义时必须保持 CLEAR，不得生成默认排序。""";
    }

    private String stateMergePrompt() {
        return """
                当前查询状态是本轮完整输出的唯一基线。先复制当前查询状态，再只修改用户本轮
                明确要求变更的类别。用户未明确修改的类别必须逐字段原样保留，禁止重新计算、
                缩短、扩大、替换或根据示例猜测。时间条件也属于普通 dimensionFilters；用户未修改
                时间时，必须按动态元数据中的字段标识原样保留已有时间过滤。
                即使用户本轮没有修改某类条件，也必须把该类条件原样放入 SET 的完整最终状态，
                或者在当前及最终均为空时返回 CLEAR。
                SET 的完整结果中不要重复 id。相对日期必须根据用户消息中提供的当前日期换算。
                用户本轮明确提出的时间、度量、分组、过滤和排序要求都必须体现在对应 action 中。""";
    }

    private String finalReviewPrompt() {
        return """
                排序字段必须已存在于当前度量或分组维度中，或者出现在本轮 SET 的完整结果中；
                不要仅生成排序而遗漏用户明确要求的分组字段。
                输出前只按上述协议检查完整性，不推断用户没有提出的查询条件。
                输出前必须再次核对：单个相对自然日只有一个 EQUALS 值；“最近 N 个自然日”的两个边界恰好覆盖 N 天；
                用户未表达排序且当前排序为空时 sortAction 必须为 CLEAR。""";
    }

    private String metadataPrompt() {
        return """
                动态元数据（以下字段标识和名称是本次运行时注入的唯一可用集合，不属于固定规则）：
                可用度量：%s。
                可用维度：%s。
                """
                .formatted(QueryMetadataCatalog.metricPrompt(), QueryMetadataCatalog.dimensionPrompt());
    }

    private String userPrompt(String message, QueryContext current) throws JsonProcessingException {
        return "当前日期：" + LocalDate.now(clock)
                + "\n本轮完整输出基线（未被用户明确修改的字段必须逐字段原样复制）："
                + objectMapper.writeValueAsString(current)
                + "\n用户本轮输入：" + message;
    }

    private ParsedQueryAction parseAndValidate(String content) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(stripMarkdownFence(content));
        JsonNode payload = root;
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("QueryAction 必须是 JSON 对象");
        }
        normalizeSortActionShape((ObjectNode) payload);
        for (String field : List.of(
                "intent", "metricAction", "dimensionAction", "filterAction", "sortAction", "explanation")) {
            if (!payload.has(field)) {
                throw new IllegalArgumentException("QueryAction 缺少字段：" + field);
            }
        }
        validateExactFields(payload, Set.of(
                "intent", "metricAction", "dimensionAction", "filterAction", "sortAction", "explanation"),
                "模型查询结果");
        String explanation = payload.path("explanation").asText("").trim();
        if (explanation.isEmpty() || explanation.length() > 200) {
            throw new IllegalArgumentException("explanation 必须是 1 至 200 字的简短说明");
        }
        validateActionJson(payload.path("metricAction"), "metricAction");
        validateActionJson(payload.path("dimensionAction"), "dimensionAction");
        validateFilterActionJson(payload.path("filterAction"));
        validateSortActionJson(payload.path("sortAction"));
        ObjectNode actionPayload = ((ObjectNode) payload).deepCopy();
        actionPayload.remove("explanation");
        QueryAction action = objectMapper.treeToValue(actionPayload, QueryAction.class);
        if (!"QUERY".equals(action.intent())) {
            throw new IllegalArgumentException("QueryAction.intent 只能是 QUERY");
        }
        QueryAction validatedAction = new QueryAction(
                "QUERY",
                validateActionPlan(
                        action.metricAction(), QueryMetadataCatalog.metricIds(), "metricAction"),
                validateActionPlan(
                        action.dimensionAction(), QueryMetadataCatalog.dimensionIds(), "dimensionAction"),
                validateFilterAction(action.filterAction()),
                validateSortAction(action.sortAction()));
        return new ParsedQueryAction(validatedAction, explanation);
    }

    private void normalizeSortActionShape(ObjectNode payload) {
        JsonNode sortAction = payload.path("sortAction");
        if (!sortAction.isObject()
                || sortAction.size() != 1
                || !sortAction.has("operations")) {
            return;
        }
        JsonNode operations = sortAction.path("operations");
        if (operations.isArray() && operations.size() == 1 && operations.get(0).isObject()) {
            JsonNode operation = operations.get(0);
            if ("CLEAR".equals(operation.path("action").asText())) {
                ObjectNode normalized = objectMapper.createObjectNode();
                normalized.put("action", "CLEAR");
                normalized.putArray("items");
                payload.set("sortAction", normalized);
                return;
            }
            payload.set("sortAction", operation.deepCopy());
        }
    }

    /**
     * Keeps the query protocol deterministic when a provider omits only the optional sorting
     * object. This does not infer a new sort: it restores exactly the sort already present in
     * the conversation state. Semantic errors in all other fields still go through validation.
     */
    private String completeMissingSortAction(String content, QueryContext current)
            throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(stripMarkdownFence(content));
        if (root == null || !root.isObject()) {
            return content;
        }
        normalizeSortActionShape((ObjectNode) root);
        JsonNode sortAction = root.path("sortAction");
        boolean missingSortShape = !sortAction.isObject()
                || !sortAction.has("action")
                || !sortAction.has("items");
        if (!missingSortShape) {
            return content;
        }
        ((ObjectNode) root).set(
                "sortAction",
                objectMapper.valueToTree(QueryAction.fromContext(current).sortAction()));
        return objectMapper.writeValueAsString(root);
    }

    private ActionPlan validateActionPlan(
            ActionPlan plan, Set<String> allowedIds, String fieldName) {
        if (plan == null || plan.operations() == null || plan.operations().size() != 1) {
            throw new IllegalArgumentException(fieldName + ".operations 必须且只能包含一个元素");
        }
        ActionOperation operation = plan.operations().get(0);
        if (operation == null || !LIST_ACTIONS.contains(operation.action())) {
            throw new IllegalArgumentException(fieldName + " 包含不支持的 action");
        }
        List<String> ids = operation.ids() == null ? List.of() : List.copyOf(operation.ids());
        List<String> unknownIds = ids.stream().filter(id -> !allowedIds.contains(id)).toList();
        if (!unknownIds.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " 包含不支持的 id：" + unknownIds);
        }
        if (new LinkedHashSet<>(ids).size() != ids.size()) {
            throw new IllegalArgumentException(fieldName + " 中的 id 不允许重复");
        }
        if ("CLEAR".equals(operation.action()) && !ids.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " 的 CLEAR 的 ids 必须为空");
        }
        if ("SET".equals(operation.action()) && ids.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " 的 SET 必须包含完整 ids");
        }
        return new ActionPlan(List.of(new ActionOperation(operation.action(), ids)));
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
            if ("CLEAR".equals(operation.action())
                    && (operations.size() != 1
                    || !operation.dimensionId().isBlank()
                    || !operation.operator().isBlank()
                    || !operation.values().isEmpty())) {
                throw new IllegalArgumentException("filterAction 的 CLEAR 必须单独出现且条件为空");
            }
            if ("SET".equals(operation.action())
                    && (!QueryMetadataCatalog.isDimension(operation.dimensionId())
                    || !FILTER_OPERATORS.contains(operation.operator())
                    || operation.values().isEmpty()
                    || operation.values().stream().anyMatch(String::isBlank))) {
                throw new IllegalArgumentException("filterAction 的 SET 条件不合法");
            }
            if ("SET".equals(operation.action())
                    && (new LinkedHashSet<>(operation.values()).size() != operation.values().size()
                    || "EQUALS".equals(operation.operator()) && operation.values().size() != 1
                    || "BETWEEN".equals(operation.operator()) && operation.values().size() != 2)) {
                throw new IllegalArgumentException("filterAction 的 SET 值数量或重复项不合法");
            }
        }
        long dimensions = operations.stream()
                .filter(operation -> "SET".equals(operation.action()))
                .map(FilterOperation::dimensionId).distinct().count();
        if (dimensions != operations.stream()
                .filter(operation -> "SET".equals(operation.action()))
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
        if ("CLEAR".equals(action.action()) && !items.isEmpty()) {
            throw new IllegalArgumentException("sortAction CLEAR 时 items 必须为空");
        }
        if ("SET".equals(action.action()) && items.isEmpty()) {
            throw new IllegalArgumentException("sortAction SET 时 items 不能为空");
        }
        Set<String> allowedFields = new LinkedHashSet<>(QueryMetadataCatalog.metricIds());
        allowedFields.addAll(QueryMetadataCatalog.dimensionIds());
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
            Set<String> missing = new LinkedHashSet<>(expected);
            missing.removeAll(actual);
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException(fieldName + " 缺少字段：" + missing);
            }
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
            ActionPlan metricAction,
            ActionPlan dimensionAction,
            FilterAction filterAction,
            SortAction sortAction) implements Serializable {

        public QueryAction {
            intent = intent == null ? "" : intent;
        }

        public static QueryAction fromContext(QueryContext context) {
            QueryContext source = context == null ? QueryContext.empty() : context;
            ActionPlan metrics = source.metricIds().isEmpty()
                    ? new ActionPlan(List.of(new ActionOperation("CLEAR", List.of())))
                    : new ActionPlan(List.of(new ActionOperation("SET", List.copyOf(source.metricIds()))));
            ActionPlan dimensions = source.dimensionIds().isEmpty()
                    ? new ActionPlan(List.of(new ActionOperation("CLEAR", List.of())))
                    : new ActionPlan(List.of(new ActionOperation("SET", List.copyOf(source.dimensionIds()))));
            FilterAction filters = source.dimensionFilters().isEmpty()
                    ? new FilterAction(List.of(new FilterOperation("CLEAR", "", "", List.of())))
                    : new FilterAction(source.dimensionFilters().stream()
                            .map(filter -> new FilterOperation(
                                    "SET", filter.dimensionId(), filter.operator(), filter.values()))
                            .toList());
            SortAction sorts = source.sorts().isEmpty()
                    ? new SortAction("CLEAR", List.of())
                    : new SortAction("SET", source.sorts().stream()
                            .map(sort -> new SortItem(sort.fieldId(), sort.direction()))
                            .toList());
            return new QueryAction(
                    "QUERY", metrics, dimensions, filters, sorts);
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

    private record ParsedQueryAction(QueryAction action, String explanation) {
    }

    public record QueryActionResult(
            QueryAction action,
            String explanation,
            LlmResultMessage llmMessage,
            List<String> normalizationNotes) {

        public QueryActionResult(QueryAction action, LlmResultMessage llmMessage) {
            this(action, "已生成完整查询状态。", llmMessage, List.of());
        }

        public QueryActionResult(
                QueryAction action, String explanation, LlmResultMessage llmMessage) {
            this(action, explanation, llmMessage, List.of());
        }

        public QueryActionResult {
            explanation = explanation == null ? "" : explanation.trim();
            normalizationNotes = normalizationNotes == null
                    ? List.of()
                    : List.copyOf(normalizationNotes);
        }
    }

    public static final class QueryInterpretationException extends IllegalArgumentException {
        private final String stage;
        private final LlmResultMessage llmMessage;

        public QueryInterpretationException(
                String stage, String message, LlmResultMessage llmMessage, Throwable cause) {
            super(message, cause);
            this.stage = stage;
            this.llmMessage = llmMessage;
        }

        public String stage() {
            return stage;
        }

        public LlmResultMessage llmMessage() {
            return llmMessage;
        }
    }
}
