package com.company.paymentanalysis.chat;

import com.company.paymentanalysis.controller.ChatQueryController.ChatRequest;
import com.company.paymentanalysis.controller.ChatQueryController.DimensionFilter;
import com.company.paymentanalysis.controller.ChatQueryController.QueryContext;
import com.company.paymentanalysis.controller.ChatQueryController.SortSpec;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient.ChatMessage;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient.LlmResultMessage;
import com.company.paymentanalysis.time.RelativeTimeResolver;
import com.company.paymentanalysis.query.QueryMetadataCatalog;
import com.company.paymentanalysis.ragflow.MetadataRetrievalTool;
import com.company.paymentanalysis.ragflow.MetadataRetrievalTool.RetrievedMetadata;
import com.company.paymentanalysis.ragflow.RetrievedMetadataPrompt;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.Serializable;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;

/**
 * Converts a natural-language request to the complete query state consumed by
 * {@link com.company.paymentanalysis.smartbi.SmartBiQueryBuilder}. The LLM sees
 * the current state and the full, current production catalog on every request;
 * it never needs to emit internal SET/CLEAR actions.
 */
@Component
public class ChatQueryInterpreter {

    private static final Set<String> FILTER_OPERATORS = Set.of(
            "EQUALS", "NOT_EQUALS", "IN", "BETWEEN",
            "GREATER", "GREATER_EQUALS", "LESS", "LESS_EQUALS");
    private static final Set<String> SORT_DIRECTIONS = Set.of("ASC", "DESC");

    private final OpenAiCompatibleLlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final RelativeTimeResolver relativeTimeResolver;
    private final MetadataRetrievalTool metadataRetrievalTool;

    @Autowired
    public ChatQueryInterpreter(
            OpenAiCompatibleLlmClient llmClient, ObjectMapper objectMapper, Clock clock,
            MetadataRetrievalTool metadataRetrievalTool) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.relativeTimeResolver = new RelativeTimeResolver(clock);
        this.metadataRetrievalTool = metadataRetrievalTool;
    }

    ChatQueryInterpreter(OpenAiCompatibleLlmClient llmClient, ObjectMapper objectMapper, Clock clock) {
        this(llmClient, objectMapper, clock, MetadataRetrievalTool.noOp());
    }

    public QueryActionResult interpret(ChatRequest request, QueryContext current) {
        try {
            List<ChatMessage> intentMessages = List.of(
                    new ChatMessage("system", intentSystemPrompt()),
                    new ChatMessage("user", "当前日期：" + LocalDate.now(clock)
                            + "\n用户输入：" + request.message()));
            String mockIntent = "{\"metrics\":[],\"groups\":[],\"filters\":[],"
                    + "\"sorts\":[],\"clears\":[]}";
            LlmResultMessage intent = complete(intentMessages, mockIntent, request.model());
            String enrichedIntent = enrichRelativeTimes(intent.content(), request.message());
            RetrievedMetadata retrievedMetadata = metadataRetrievalTool
                    .retrieveForQuery(request.message(), enrichedIntent);

            List<ChatMessage> mappingMessages = List.of(
                    new ChatMessage("system", systemPrompt(retrievedMetadata)),
                    new ChatMessage("user", mappingPrompt(request.message(), current, enrichedIntent)));
            ObjectNode mockPayload = objectMapper.valueToTree(QueryAction.fromContext(current));
            mockPayload.put("explanation", "沿用当前查询状态，未增加额外查询条件。");
            String mockContent = objectMapper.writeValueAsString(mockPayload);
            LlmResultMessage mapped = complete(mappingMessages, mockContent, request.model());
            try {
                ParsedQueryAction parsed = parseAndValidate(mapped.content(), enrichedIntent);
                return new QueryActionResult(parsed.action(), parsed.explanation(), mapped);
            } catch (JsonProcessingException | RuntimeException mappingError) {
                throw new QueryInterpretationException(
                        "QUERY_STATE_VALIDATION",
                        "字段映射后的 QueryState JSON 校验失败：" + conciseMessage(mappingError),
                        mapped,
                        mappingError);
            }
        } catch (QueryInterpretationException exception) {
            throw exception;
        } catch (JsonProcessingException | RuntimeException exception) {
            throw new QueryInterpretationException(
                    "QUERY_STATE_VALIDATION",
                    "QueryState JSON 校验失败：" + conciseMessage(exception),
                    null,
                    exception);
        }
    }

    public String engineLabel() {
        return llmClient.modelLabel();
    }

    public String engineLabel(String model) {
        return StringUtils.hasText(model) ? llmClient.modelLabel(model) : engineLabel();
    }

    String systemPrompt() {
        return systemPrompt(MetadataRetrievalTool.RetrievedMetadata.empty());
    }

    String systemPrompt(RetrievedMetadata retrievedMetadata) {
        return """
                你是支付数据查询状态生成器。结合当前查询状态和用户输入，只返回处理后的完整 QueryState JSON。
                不要 Markdown、解释性文字或额外字段。

                顶层字段必须且只能为：metricIds、dimensionIds、dimensionFilters、sorts、explanation。
                固定结构：
                {"metricIds":["..."],"dimensionIds":["..."],"dimensionFilters":[{"dimensionId":"...","operator":"EQUALS","values":["..."]}],"sorts":[{"fieldId":"...","direction":"ASC"}],"explanation":"..."}

                规则：
                1. 输出本轮处理后的完整最终查询状态，不是增量操作；用户未明确修改的条件保留当前状态。
                2. 无分组时 dimensionIds=[]；无过滤时 dimensionFilters=[]；无排序时 sorts=[]。
                3. 所有字段 ID 只能使用动态元数据中的 ID；不要编造字段。
                4. operator 只能是 EQUALS、NOT_EQUALS、IN、BETWEEN、GREATER、GREATER_EQUALS、LESS、LESS_EQUALS；direction 只能是 ASC、DESC。
                5. 用户明确说出的每个时间、地区、机构、渠道、交易类型等限制都必须进入 dimensionFilters，不能只写在 explanation 中。
                5.1 用户消息中附带的“语义清单”是第一阶段提取结果：其中每个 metrics、groups、filters、sorts 项都必须在最终 JSON 中有对应项，不得遗漏；resolvedValues 是已计算好的确定时间值，直接使用。
                6. dimensionIds 只放用户明确要求“按、各、每、分组、趋势”等展示粒度的维度。时间范围本身不是分组；用户只说“近N天/月”时不要自动增加时间分组或排序。
                7. 单点时间用 EQUALS，连续时间范围用 BETWEEN。最近N天/月包含当前日/月：结束值为当前日/月，开始值为向前推N-1个日/月；日期格式为yyyy-MM-dd，月份格式为yyyy-MM。
                8. 地名、国家、地区、洲际等成员值必须映射到语义最接近的地域维度并成为过滤条件。未明确说发卡方时优先选择受理或收单地域维度；明确说发卡方时选择发卡地域维度。
                9. 只有用户明确提出升序、降序、排名、最高、最低等要求时才生成 sorts，不得自行增加时间升序。
                10. 输出前核对 explanation：其中出现的每个时间和范围限制都必须存在于 dimensionFilters；出现的分组和排序必须分别存在于 dimensionIds 和 sorts。
                11. explanation 用简短中文说明最终查询，不超过100字。

                示例（仅说明时间范围不是分组，字段仍以动态元数据为准）：
                用户输入“海外地区总交易笔数，近3个月”时，应输出总交易笔数度量、空 dimensionIds、海外地区过滤、月份 BETWEEN 过滤、空 sorts。

                动态元数据候选：
                %s
                """.formatted(metadataPrompt(retrievedMetadata));
    }

    private String metadataPrompt(RetrievedMetadata retrievedMetadata) {
        if (retrievedMetadata != null && !retrievedMetadata.isEmpty()) {
            return RetrievedMetadataPrompt.render(retrievedMetadata);
        }
        return "可用度量：" + QueryMetadataCatalog.metricPrompt()
                + "。可用维度：" + QueryMetadataCatalog.dimensionPrompt();
    }

    private String mappingPrompt(String message, QueryContext current, String semanticIntent)
            throws JsonProcessingException {
        return "当前日期：" + LocalDate.now(clock)
                + "\n当前查询状态：" + objectMapper.writeValueAsString(
                        current == null ? QueryContext.empty() : current)
                + "\n用户原始输入：" + message
                + "\n第一阶段语义清单：" + semanticIntent;
    }

    private String intentSystemPrompt() {
        return """
                你是查询语义清单提取器。只提取用户本轮明确说出的要求，不选择数据库字段 ID、不计算日期。
                只返回 JSON，固定结构：
                {"metrics":["用户原词"],"groups":["用户原词"],"filters":[{"category":"GEOGRAPHY|TIME|ORGANIZATION|CHANNEL|TRANSACTION_TYPE|OTHER","raw":"用户原词","operator":"EQUALS|IN|RELATIVE","values":["用户原值"],"relativeTime":{"kind":"TODAY|YESTERDAY|CURRENT_MONTH_TO_DATE|CURRENT_YEAR_TO_DATE|LAST_N","unit":"DAY|MONTH|YEAR","count":1}}],"sorts":[{"raw":"用户原词","direction":"ASC|DESC"}],"clears":[]}

                严格规则：
                1. 逐项覆盖度量、分组、时间、地域、机构、渠道、交易类型、排序，不得遗漏或添加。
                2. 地名、国家、地区、洲际如果没有“按、各、每、分组”等词，一律是 GEOGRAPHY 过滤，不是分组；values 必须保留用户说出的地域值。
                3. 相对时间用 TIME + RELATIVE，只提取原词，不换算日期：今天=TODAY，昨天=YESTERDAY，本月至今=CURRENT_MONTH_TO_DATE，今年至今=CURRENT_YEAR_TO_DATE，近/最近 N 天、月、年=LAST_N 且填写 unit、count=N；本月、今年也保留原词并填写对应 unit、count=1。
                4. 没有的数组返回 []。非相对时间的 relativeTime 返回 null。
                """;
    }

    private String enrichRelativeTimes(String content, String userMessage) {
        try {
            JsonNode root = objectMapper.readTree(stripMarkdownFence(content));
            if (!(root instanceof ObjectNode objectRoot) || !root.path("filters").isArray()) {
                return content;
            }
            var resolvedFromMessage = relativeTimeResolver.resolveQueryTime(userMessage);
            boolean hasMessageTimeFilter = false;
            for (JsonNode filter : root.path("filters")) {
                if (!(filter instanceof ObjectNode objectFilter)) {
                    continue;
                }
                if (resolvedFromMessage.isPresent() && "TIME".equals(filter.path("category").asText())) {
                    applyResolvedTime(objectFilter, resolvedFromMessage.get());
                    hasMessageTimeFilter = true;
                    continue;
                }
                if (!"RELATIVE".equals(filter.path("operator").asText())) {
                    continue;
                }
                JsonNode relative = filter.path("relativeTime");
                int count = relative.path("count").asInt(0);
                String unit = relative.path("unit").asText("");
                String kind = relative.path("kind").asText("");
                var resolved = relativeTimeResolver.resolveQueryTime(
                        filter.path("raw").asText(""), kind, unit, count);
                if (resolved.isPresent()) {
                    applyResolvedTime(objectFilter, resolved.get());
                }
            }
            if (resolvedFromMessage.isPresent() && !hasMessageTimeFilter) {
                ObjectNode timeFilter = objectRoot.withArray("filters").addObject();
                timeFilter.put("category", "TIME");
                timeFilter.put("raw", "服务端已识别的时间条件");
                timeFilter.put("operator", "RELATIVE");
                timeFilter.set("values", objectMapper.createArrayNode());
                timeFilter.putNull("relativeTime");
                applyResolvedTime(timeFilter, resolvedFromMessage.get());
            }
            return objectMapper.writeValueAsString(objectRoot);
        } catch (JsonProcessingException | RuntimeException ignored) {
            return content;
        }
    }

    private void applyResolvedTime(
            ObjectNode target, RelativeTimeResolver.ResolvedQueryTime resolved) {
        target.put("resolvedOperator", resolved.operator());
        target.set("resolvedValues", objectMapper.valueToTree(resolved.values()));
        target.put("resolvedDimensionId", resolved.dimensionId());
    }

    private LlmResultMessage complete(List<ChatMessage> messages, String mockContent, String model) {
        return StringUtils.hasText(model)
                ? llmClient.completeWithMessage(messages, mockContent, model)
                : llmClient.completeWithMessage(messages, mockContent);
    }

    private ParsedQueryAction parseAndValidate(String content, String enrichedIntent) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(stripMarkdownFence(content));
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("QueryState 必须是 JSON 对象");
        }
        Set<String> expected = Set.of(
                "metricIds", "dimensionIds", "dimensionFilters", "sorts", "explanation");
        validateExactFields(root, expected, "QueryState");
        String explanation = root.path("explanation").asText("").trim();
        if (explanation.isEmpty() || explanation.length() > 200) {
            throw new IllegalArgumentException("explanation 必须是1至200字的简短说明");
        }
        ObjectNode actionPayload = ((ObjectNode) root).deepCopy();
        actionPayload.remove("explanation");
        QueryAction action = objectMapper.treeToValue(actionPayload, QueryAction.class);
        return new ParsedQueryAction(validate(applyResolvedTimeFilters(action, enrichedIntent)), explanation);
    }

    /**
     * The server owns relative-date arithmetic. Once it resolved a time phrase,
     * the model may not replace it with a different precision or date value.
     */
    private QueryAction applyResolvedTimeFilters(QueryAction action, String enrichedIntent)
            throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(enrichedIntent);
        List<DimensionFilter> resolved = new java.util.ArrayList<>();
        for (JsonNode filter : root.path("filters")) {
            String dimensionId = filter.path("resolvedDimensionId").asText("");
            String operator = filter.path("resolvedOperator").asText("");
            List<String> values = new java.util.ArrayList<>();
            for (JsonNode value : filter.path("resolvedValues")) {
                if (value.isTextual() && StringUtils.hasText(value.asText())) {
                    values.add(value.asText());
                }
            }
            if (isTimeDimension(dimensionId) && FILTER_OPERATORS.contains(operator) && !values.isEmpty()) {
                resolved.add(new DimensionFilter(dimensionId, operator, values));
            }
        }
        if (resolved.isEmpty()) {
            return action;
        }
        List<DimensionFilter> filters = new java.util.ArrayList<>();
        for (DimensionFilter filter : action.dimensionFilters()) {
            if (!isTimeDimension(filter.dimensionId())) {
                filters.add(filter);
            }
        }
        filters.addAll(resolved);
        return new QueryAction(action.metricIds(), action.dimensionIds(), filters, action.sorts());
    }

    private QueryAction validate(QueryAction action) {
        List<String> metrics = identifiers(action.metricIds(), QueryMetadataCatalog.metricIds(), "metricIds");
        List<String> dimensions = identifiers(
                action.dimensionIds(), QueryMetadataCatalog.dimensionIds(), "dimensionIds");
        List<DimensionFilter> filters = action.dimensionFilters().stream().map(filter -> {
            if (filter == null
                    || !QueryMetadataCatalog.isDimension(filter.dimensionId())
                    || !FILTER_OPERATORS.contains(filter.operator())
                    || filter.values() == null
                    || filter.values().isEmpty()
                    || filter.values().stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("dimensionFilters 包含不合法的过滤条件");
            }
            if (new LinkedHashSet<>(filter.values()).size() != filter.values().size()) {
                throw new IllegalArgumentException("dimensionFilters 的过滤值不允许重复");
            }
            DimensionFilter normalized = new DimensionFilter(filter.dimensionId(), filter.operator(), List.copyOf(filter.values()));
            validateTimeFilter(normalized);
            return normalized;
        }).toList();
        Set<String> availableSortFields = new LinkedHashSet<>(QueryMetadataCatalog.metricIds());
        availableSortFields.addAll(QueryMetadataCatalog.dimensionIds());
        List<SortSpec> sorts = action.sorts().stream().map(sort -> {
            if (sort == null || !availableSortFields.contains(sort.fieldId())
                    || !SORT_DIRECTIONS.contains(sort.direction())) {
                throw new IllegalArgumentException("sorts 包含不合法的排序条件");
            }
            return new SortSpec(sort.fieldId(), sort.direction());
        }).toList();
        if (new LinkedHashSet<>(sorts.stream().map(SortSpec::fieldId).toList()).size() != sorts.size()) {
            throw new IllegalArgumentException("sorts 的排序字段不允许重复");
        }
        return new QueryAction(metrics, dimensions, filters, sorts);
    }

    private static boolean isTimeDimension(String dimensionId) {
        return Set.of(
                RelativeTimeResolver.YEAR_FIELD,
                RelativeTimeResolver.MONTH_FIELD,
                RelativeTimeResolver.DAY_FIELD).contains(dimensionId);
    }

    private void validateTimeFilter(DimensionFilter filter) {
        if (!isTimeDimension(filter.dimensionId())) {
            return;
        }
        if ("EQUALS".equals(filter.operator()) && filter.values().size() != 1) {
            throw new IllegalArgumentException("时间 EQUALS 过滤必须且只能有一个值");
        }
        if ("BETWEEN".equals(filter.operator()) && filter.values().size() != 2) {
            throw new IllegalArgumentException("时间 BETWEEN 过滤必须包含起止两个值");
        }
        List<String> parsed = filter.values().stream()
                .map(value -> parseTimeValue(filter.dimensionId(), value))
                .toList();
        if ("BETWEEN".equals(filter.operator()) && parsed.get(0).compareTo(parsed.get(1)) > 0) {
            throw new IllegalArgumentException("时间 BETWEEN 过滤的开始值不能晚于结束值");
        }
    }

    private String parseTimeValue(String dimensionId, String value) {
        try {
            return switch (dimensionId) {
                case RelativeTimeResolver.YEAR_FIELD -> {
                    if (!value.matches("\\d{4}")) {
                        throw new IllegalArgumentException("年字段只接受 yyyy 格式");
                    }
                    yield Integer.toString(Integer.parseInt(value));
                }
                case RelativeTimeResolver.MONTH_FIELD -> YearMonth.parse(value).toString();
                case RelativeTimeResolver.DAY_FIELD -> LocalDate.parse(value).toString();
                default -> throw new IllegalArgumentException("未知时间字段");
            };
        } catch (DateTimeException | NumberFormatException exception) {
            String expected = switch (dimensionId) {
                case RelativeTimeResolver.MONTH_FIELD -> "yyyy-MM";
                case RelativeTimeResolver.DAY_FIELD -> "yyyy-MM-dd";
                default -> "yyyy";
            };
            throw new IllegalArgumentException(QueryMetadataCatalog.displayName(dimensionId)
                    + "字段只接受 " + expected + " 格式", exception);
        }
    }

    private List<String> identifiers(List<String> ids, Set<String> allowed, String fieldName) {
        if (ids == null || ids.stream().anyMatch(id -> id == null || !allowed.contains(id))) {
            throw new IllegalArgumentException(fieldName + " 包含不支持的字段 ID");
        }
        if (new LinkedHashSet<>(ids).size() != ids.size()) {
            throw new IllegalArgumentException(fieldName + " 的字段 ID 不允许重复");
        }
        return List.copyOf(ids);
    }

    private void validateExactFields(JsonNode node, Set<String> expected, String fieldName) {
        Set<String> actual = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(fieldName + " 的字段必须为：" + expected);
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

    private String conciseMessage(Throwable throwable) {
        String message = throwable == null ? "未知错误" : throwable.getMessage();
        if (!StringUtils.hasText(message)) {
            message = throwable == null ? "未知错误" : throwable.getClass().getSimpleName();
        }
        String normalized = message.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s{2,}", " ").trim();
        return normalized.length() <= 400 ? normalized : normalized.substring(0, 400) + "…";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QueryAction(
            List<String> metricIds,
            List<String> dimensionIds,
            List<DimensionFilter> dimensionFilters,
            List<SortSpec> sorts) implements Serializable {

        public QueryAction {
            metricIds = metricIds == null ? List.of() : List.copyOf(metricIds);
            dimensionIds = dimensionIds == null ? List.of() : List.copyOf(dimensionIds);
            dimensionFilters = dimensionFilters == null ? List.of() : List.copyOf(dimensionFilters);
            sorts = sorts == null ? List.of() : List.copyOf(sorts);
        }

        public static QueryAction fromContext(QueryContext context) {
            QueryContext source = context == null ? QueryContext.empty() : context;
            return new QueryAction(
                    source.metricIds(), source.dimensionIds(), source.dimensionFilters(), source.sorts());
        }

        public QueryContext toContext() {
            return new QueryContext(metricIds, dimensionIds, dimensionFilters, sorts);
        }
    }

    private record ParsedQueryAction(QueryAction action, String explanation) {
    }

    public record QueryActionResult(QueryAction action, String explanation, LlmResultMessage llmMessage) {
        public QueryActionResult {
            explanation = explanation == null ? "" : explanation.trim();
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
