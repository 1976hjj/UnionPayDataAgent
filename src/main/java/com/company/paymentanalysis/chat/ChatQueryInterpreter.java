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
import com.company.paymentanalysis.semantic.BusinessSemanticProcessor;
import com.company.paymentanalysis.semantic.BusinessSemanticProcessor.AppliedSemanticRule;
import com.company.paymentanalysis.semantic.BusinessSemanticProcessor.NormalizationResult;
import com.company.paymentanalysis.semantic.MetadataSemanticGrounder;
import com.company.paymentanalysis.semantic.MetadataSemanticGrounder.AmbiguousGrounding;
import com.company.paymentanalysis.semantic.MetadataSemanticGrounder.GroundedFilter;
import com.company.paymentanalysis.semantic.MetadataSemanticGrounder.GroundingResult;
import com.company.paymentanalysis.semantic.QuerySemanticIntent;
import com.company.paymentanalysis.semantic.QuerySemanticIntent.FilterTerm;
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
 * Converts the latest natural-language turn to the complete query state consumed by
 * {@link com.company.paymentanalysis.smartbi.SmartBiQueryBuilder}. Retrieval is scoped
 * to the latest turn while previously grounded slots remain sticky until the user
 * explicitly replaces or removes them.
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
    private final MetadataRetrievalTool metadataRetrievalTool;
    private final BusinessSemanticProcessor businessSemanticProcessor;

    @Autowired
    public ChatQueryInterpreter(
            OpenAiCompatibleLlmClient llmClient, ObjectMapper objectMapper, Clock clock,
            MetadataRetrievalTool metadataRetrievalTool,
            BusinessSemanticProcessor businessSemanticProcessor) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.metadataRetrievalTool = metadataRetrievalTool;
        this.businessSemanticProcessor = businessSemanticProcessor;
    }

    ChatQueryInterpreter(OpenAiCompatibleLlmClient llmClient, ObjectMapper objectMapper, Clock clock) {
        this(llmClient, objectMapper, clock, MetadataRetrievalTool.noOp(), BusinessSemanticProcessor.noOp());
    }

    ChatQueryInterpreter(
            OpenAiCompatibleLlmClient llmClient, ObjectMapper objectMapper, Clock clock,
            MetadataRetrievalTool metadataRetrievalTool) {
        this(llmClient, objectMapper, clock, metadataRetrievalTool, BusinessSemanticProcessor.noOp());
    }

    public QueryActionResult interpret(ChatRequest request, QueryContext current) {
        return interpret(request, current, request.pendingQueryIntent());
    }

    public QueryActionResult interpret(
            ChatRequest request, QueryContext current, String pendingQueryIntent) {
        try {
            List<ChatMessage> intentMessages = List.of(
                    new ChatMessage("system", intentSystemPrompt()),
                    new ChatMessage("user", intentPrompt(request.message(), pendingQueryIntent)));
            String mockIntent = "{\"searchTerms\":[],\"metricTerms\":[],\"groupTerms\":[],\"filterTerms\":[],"
                    + "\"sortTerms\":[],\"unmappedTerms\":[]}";
            LlmResultMessage intent = complete(intentMessages, mockIntent, request.model());
            QuerySemanticIntent parsedIntent = sanitizeSearchTerms(
                    objectMapper.readValue(stripMarkdownFence(intent.content()), QuerySemanticIntent.class),
                    request.message(), pendingQueryIntent);
            NormalizationResult normalization = businessSemanticProcessor.normalize(request.message(), parsedIntent);
            QuerySemanticIntent normalizedIntent = normalization.intent();
            String semanticIntent = objectMapper.writeValueAsString(normalizedIntent);
            RetrievedMetadata retrievedMetadata = metadataRetrievalTool
                    .retrieveForQuery(request.message(), semanticIntent);
            GroundingResult grounding = MetadataSemanticGrounder.ground(normalizedIntent, retrievedMetadata);

            List<ChatMessage> mappingMessages = List.of(
                    new ChatMessage("system", systemPrompt(retrievedMetadata, grounding)),
                    new ChatMessage("user", mappingPrompt(
                            request.message(), current, semanticIntent)));
            QueryAction currentAction = QueryAction.fromContext(current);
            String mockContent = objectMapper.writeValueAsString(java.util.Map.of(
                    "metricIds", currentAction.metricIds(),
                    "dimensionIds", currentAction.dimensionIds(),
                    "dimensionFilters", currentAction.dimensionFilters(),
                    "sorts", currentAction.sorts(),
                    "unresolvedItems", List.of()));
            LlmResultMessage mapped = complete(mappingMessages, mockContent, request.model());
            try {
                ParsedQueryAction parsed = parseAndValidate(
                        mapped.content(), normalizedIntent, currentAction,
                        retrievedMetadata, grounding, normalization, request.message());
                return new QueryActionResult(
                        parsed.action(), parsed.explanation(), mapped, semanticIntent,
                        parsed.unresolvedItems(), parsed.pendingResolutions(), normalization.appliedRules(),
                        parsed.ambiguousResolutions());
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
        return systemPrompt(MetadataRetrievalTool.RetrievedMetadata.empty(), GroundingResult.empty());
    }

    String systemPrompt(RetrievedMetadata retrievedMetadata) {
        return systemPrompt(retrievedMetadata, GroundingResult.empty());
    }

    private String systemPrompt(RetrievedMetadata retrievedMetadata, GroundingResult grounding) {
        return """
                你是支付数据查询状态生成器。结合当前查询状态和用户输入，只返回处理后的完整 QueryState JSON。
                不要 Markdown、解释性文字或额外字段。

                顶层字段必须且只能为：metricIds、dimensionIds、dimensionFilters、sorts、unresolvedItems。
                固定结构：
                {"metricIds":["..."],"dimensionIds":["..."],"dimensionFilters":[{"dimensionId":"...","operator":"EQUALS","values":["..."]}],"sorts":[{"fieldId":"...","direction":"ASC"}],"unresolvedItems":["用户原词"]}

                规则：
                1. 输出本轮处理后的完整最终查询状态，不是增量操作。
                2. 第一阶段语义清单描述本轮必须完整覆盖的用户语义目标，但其中的槽位归类只是初步判断。不得静默删除、增加或改变用户业务含义；可以依据确定性语义落槽和严格元数据证据，将复合原词在度量、维度、过滤之间重新归槽。每个原始语义必须最终映射、被规则消费，或进入 unresolvedItems。
                3. 不得静默遗漏任何仍有效要求。无法可靠映射时，保留已确认状态并放入 unresolvedItems；不得编造字段或值。
                4. 所有字段 ID 只能使用动态元数据允许的内容。过滤值逐字保留用户给出的值；时间值按字段格式规范化。
                5. 无分组时 dimensionIds=[]；无过滤时 dimensionFilters=[]；无排序时 sorts=[]。
                6. 在输出前自行核对：每项历史意图和本轮要求均已映射、明确移除，或进入 unresolvedItems。
                8. 单值过滤使用 EQUALS；多个离散值使用 IN；连续起止范围使用 BETWEEN。不得为了通过格式校验而删除用户明确要求的条件。
                9. 优先使用检索候选；若候选未召回、但完整允许字段中存在唯一且合理的映射，仍可输出该字段，系统会要求用户确认。无法映射或存在多个合理候选时不要猜，把用户原词逐项放入 unresolvedItems；全部映射完成时返回 []。
                10. 一个独立度量原词最多映射一个度量字段。模糊总称不能展开成一组度量；无法唯一确定时 metricIds 不增加字段，并把该原词放入 unresolvedItems。
                11. 时间字段和值的精度必须严格一致。xxxx_Year 的每个值只能是 yyyy；xxxx_Month 的每个值只能是 yyyy-MM；xxxx_Day 的每个值只能是 yyyy-MM-dd。不要把日期范围填入年或月字段。
                12. requiredFilters 是系统根据严格且唯一的值域证据生成的确定性落槽，必须完整写入 QueryState。普通 filterValueCandidates 只是检索候选，不得全部自动采用。
                13. ambiguousFilters 表示同一原值对应多个合理字段。不得同时加入多个候选字段；能依据用户明确方向语义唯一确定时只选择一个，否则将 sourceTerm 放入 unresolvedItems。

                动态元数据候选：
                %s

                确定性语义落槽：
                %s
                """.formatted(metadataPrompt(retrievedMetadata), groundingPrompt(grounding));
    }

    private String groundingPrompt(GroundingResult grounding) {
        GroundingResult source = grounding == null ? GroundingResult.empty() : grounding;
        String required = source.requiredFilters().isEmpty()
                ? "requiredFilters: []"
                : "requiredFilters:\n" + source.requiredFilters().stream()
                        .map(filter -> "- sourceTerm=" + filter.sourceTerm()
                                + "; fieldId=" + filter.dimensionId()
                                + "; name=" + filter.dimensionName()
                                + "; operator=EQUALS; value=" + filter.matchedValue())
                        .collect(java.util.stream.Collectors.joining("\n"));
        String ambiguous = source.ambiguousFilters().isEmpty()
                ? "ambiguousFilters: []"
                : "ambiguousFilters:\n" + source.ambiguousFilters().stream()
                        .map(item -> "- sourceTerm=" + item.sourceTerm()
                                + "; value=" + item.matchedValue()
                                + "; candidateFieldIds=" + String.join(",", item.dimensionIds()))
                        .collect(java.util.stream.Collectors.joining("\n"));
        return required + "\n" + ambiguous;
    }

    private String metadataPrompt(RetrievedMetadata retrievedMetadata) {
        RetrievedMetadata metadata = retrievedMetadata == null ? RetrievedMetadata.empty() : retrievedMetadata;
        String evidence = metadata.isEmpty() ? "未检索到候选。" : RetrievedMetadataPrompt.render(metadata)
                .replace("Only select field IDs present in these candidates. ",
                        "These candidates are retrieval evidence; the complete field allow-list follows. ");
        return evidence + "\n完整允许度量字段：" + QueryMetadataCatalog.metricPrompt()
                + "\n完整允许维度字段：" + QueryMetadataCatalog.dimensionPrompt();
    }

    private String mappingPrompt(
            String message, QueryContext current, String semanticIntent)
            throws JsonProcessingException {
        return "当前日期：" + LocalDate.now(clock)
                + "\n当前查询状态：" + objectMapper.writeValueAsString(
                        current == null ? QueryContext.empty() : current)
                + "\n用户原始输入：" + message
                + "\n第一阶段语义清单：" + semanticIntent;
    }

    private String intentPrompt(String message, String pendingQueryIntent) {
        return "当前日期：" + LocalDate.now(clock)
                + "\n上轮未映射语义："
                + (pendingQueryIntent == null || pendingQueryIntent.isBlank() ? "无" : pendingQueryIntent)
                + "\n用户最新一轮要求：" + message
                + "\n只提取最新一轮以及上轮未映射语义中明确出现的业务元素；"
                + "不要复述或补入已经存在于历史查询状态中的字段和值。";
    }

    private String intentSystemPrompt() {
        return """
                你是查询最新轮语义清单提取器。只处理用户最新一轮要求和上轮尚未映射的语义；不选择数据库字段 ID，不发明业务含义。
                只返回 JSON，固定结构：
                {"searchTerms":[{"text":"用户原话中的业务术语","context":"该词所在原句"}],"metricTerms":["度量原词"],"groupTerms":["分组维度原词"],"filterTerms":[{"dimensionTerm":"过滤维度原词","operator":"EQUALS|NOT_EQUALS|IN|BETWEEN|GREATER|GREATER_EQUALS|LESS|LESS_EQUALS","values":["用户原值"],"context":"过滤条件原句"}],"sortTerms":[{"fieldTerm":"排序字段原词","direction":"ASC|DESC"}],"unmappedTerms":[]}

                规则：
                1. metricTerms、groupTerms、filterTerms、sortTerms 只输出最新一轮明确提到的内容。历史中已经确定但本轮没有提到的度量、维度、过滤和值不得重复输出。
                2. 一句话中混合度量和条件时必须拆开。例如“发卡IIN为47300702航空类承兑金额”应拆为度量“承兑金额”和两个独立过滤条件，不得把整句当度量。
                3. filterTerms.dimensionTerm 表示用户所指的过滤维度，values 只放用户给出的值，context 保留原句。不要把长编号拆成短数字，也不要根据编号内容猜字段。
                4. 相对时间允许根据当前日期换算成明确日期值，但不得选择字段 ID。例如“昨天”可输出 dimensionTerm=“日”、values=["yyyy-MM-dd"]；“对比去年5月和4月”同时是按月分组和月份过滤。
                5. 无法判断属于哪个槽位的原词放入 unmappedTerms，不要硬塞进某个槽位；不使用的数组返回 []。
                6. 时间粒度必须先与用户原话一致。“今年/本年”或明确的“2026年”必须输出 dimensionTerm="年"、operator="EQUALS"、values=["2026"]；不要把完整年份写成 ["2026-01-01","2026-12-31"] 的 BETWEEN。只有用户明确要求按日或给出日期起止范围时，才输出日粒度日期值。
                7. searchTerms 用于业务黑话知识召回。把完整最终语义中的英文缩写、括号内中文术语、业务黑话和模糊业务总称分别提取；text 必须逐字来自用户本轮原话或“上轮未映射语义”，不得改写、翻译或发明同义词。例如“VCC（虚拟商务卡）总体业务情况”分别提取 VCC、虚拟商务卡、总体业务情况。
                8. 即使某个黑话暂时无法归入度量、分组或过滤槽位，也必须保留在 searchTerms 和 unmappedTerms 中，以便知识规则先召回后再归槽。
                """;
    }

    private LlmResultMessage complete(List<ChatMessage> messages, String mockContent, String model) {
        return StringUtils.hasText(model)
                ? llmClient.completeWithMessage(messages, mockContent, model)
                : llmClient.completeWithMessage(messages, mockContent);
    }

    private ParsedQueryAction parseAndValidate(
            String content, QuerySemanticIntent semanticIntent, QueryAction currentAction,
            RetrievedMetadata retrievedMetadata, GroundingResult grounding, NormalizationResult normalization,
            String userMessage)
            throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(stripMarkdownFence(content));
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("QueryState 必须是 JSON 对象");
        }
        Set<String> expected = Set.of(
                "metricIds", "dimensionIds", "dimensionFilters", "sorts", "unresolvedItems");
        validateExactFields(root, expected, "QueryState");
        ObjectNode actionPayload = ((ObjectNode) root).deepCopy();
        actionPayload.remove("unresolvedItems");
        QueryAction action = objectMapper.treeToValue(actionPayload, QueryAction.class);
        action = retainSupportedGeneratedFilters(
                action, currentAction, semanticIntent, retrievedMetadata);
        List<String> unresolvedItems = new java.util.ArrayList<>(
                textList(root.path("unresolvedItems"), "unresolvedItems"));
        int metricTermCount = (int) semanticIntent.metricTerms().stream()
                .filter(StringUtils::hasText).distinct().count();
        int newlyMappedMetricCount = (int) action.metricIds().stream()
                .filter(id -> !currentAction.metricIds().contains(id))
                .count();
        if (newlyMappedMetricCount > metricTermCount) {
            action = new QueryAction(
                    currentAction.metricIds(), action.dimensionIds(), action.dimensionFilters(), action.sorts());
            semanticIntent.metricTerms().stream()
                    .filter(StringUtils::hasText)
                    .filter(term -> !unresolvedItems.contains(term))
                    .forEach(unresolvedItems::add);
        }
        action = applyExplicitAmbiguitySelection(action, currentAction, grounding, userMessage);
        action = reconcileGroundings(action, grounding);
        action = applyRuleConstraints(action, normalization);
        action = mergeStickyState(
                currentAction, action, semanticIntent, retrievedMetadata, grounding, normalization,
                userMessage);
        unresolvedItems.removeIf(item -> normalization.consumedTerms().stream()
                .anyMatch(term -> normalizeTerm(item).contains(normalizeTerm(term))));
        applyGroundingCoverage(action, grounding, unresolvedItems);
        removeItemsCoveredByFinalFilters(action, unresolvedItems);
        List<AmbiguousResolution> ambiguousResolutions = ambiguousResolutions(action, grounding);
        List<PendingResolution> pendingResolutions = pendingResolutions(
                action, currentAction, semanticIntent, retrievedMetadata, normalization);
        String explanation = unresolvedItems.isEmpty()
                ? pendingResolutions.isEmpty()
                        ? "已按元数据完成查询条件映射。"
                        : "存在待用户确认的弱证据映射。"
                : "未找到元数据映射：" + String.join("、", unresolvedItems) + "。";
        return new ParsedQueryAction(
                validate(action), explanation, List.copyOf(unresolvedItems), pendingResolutions,
                ambiguousResolutions);
    }

    /**
     * Resolves any ambiguity generically when the user names exactly one of the
     * recalled candidate fields. The original matched value is applied to that
     * field; no business term or field ID is hard-coded here.
     */
    private QueryAction applyExplicitAmbiguitySelection(
            QueryAction source, QueryAction currentAction,
            GroundingResult grounding, String userMessage) {
        GroundingResult evidence = grounding == null ? GroundingResult.empty() : grounding;
        String reply = normalizeTerm(userMessage);
        if (reply.isBlank() || evidence.ambiguousFilters().isEmpty()) return source;

        List<DimensionFilter> filters = new java.util.ArrayList<>(source.dimensionFilters());
        LinkedHashSet<String> dimensions = new LinkedHashSet<>(source.dimensionIds());
        boolean explicitGrouping = containsExplicitGroupingIntent(userMessage);
        for (AmbiguousGrounding ambiguous : evidence.ambiguousFilters()) {
            List<com.company.paymentanalysis.semantic.MetadataSemanticGrounder.GroundingCandidate> selected =
                    ambiguous.candidates().stream()
                            .filter(candidate -> selectsCandidate(
                                    reply, candidate.dimensionId(), candidate.dimensionName()))
                            .toList();
            if (selected.size() != 1) continue;
            var candidate = selected.get(0);
            boolean conflicting = filters.stream().anyMatch(filter ->
                    candidate.dimensionId().equals(filter.dimensionId())
                            && !hasValue(filter, ambiguous.matchedValue()));
            if (conflicting) continue;
            boolean alreadyPresent = filters.stream().anyMatch(filter ->
                    candidate.dimensionId().equals(filter.dimensionId())
                            && hasValue(filter, ambiguous.matchedValue()));
            if (!alreadyPresent) {
                filters.add(new DimensionFilter(
                        candidate.dimensionId(), "EQUALS", List.of(ambiguous.matchedValue())));
            }
            boolean wasAlreadyGrouped = currentAction != null
                    && currentAction.dimensionIds().contains(candidate.dimensionId());
            if (!explicitGrouping && !wasAlreadyGrouped) dimensions.remove(candidate.dimensionId());
        }
        return new QueryAction(
                source.metricIds(), List.copyOf(dimensions), List.copyOf(filters), source.sorts());
    }

    private boolean selectsCandidate(String normalizedReply, String fieldId, String fieldName) {
        String normalizedId = normalizeTerm(fieldId);
        String normalizedName = normalizeTerm(fieldName);
        if (normalizedReply.equals(normalizedId) || normalizedReply.equals(normalizedName)) return true;
        String selection = normalizedReply
                .replaceFirst("^(?:我选|选择|选|就用|使用|用|我要|按)", "")
                .replaceFirst("(?:这个|就行|即可|吧)$", "");
        return selection.equals(normalizedId) || selection.equals(normalizedName);
    }

    private boolean containsExplicitGroupingIntent(String message) {
        String normalized = normalizeTerm(message);
        return normalized.contains("分组") || normalized.contains("分别")
                || normalized.contains("下钻") || normalized.contains("各个");
    }

    private List<AmbiguousResolution> ambiguousResolutions(
            QueryAction action, GroundingResult grounding) {
        GroundingResult source = grounding == null ? GroundingResult.empty() : grounding;
        return source.ambiguousFilters().stream()
                .filter(ambiguous -> action.dimensionFilters().stream()
                        .filter(filter -> ambiguous.dimensionIds().contains(filter.dimensionId())
                                && hasValue(filter, ambiguous.matchedValue()))
                        .count() != 1)
                .map(ambiguous -> new AmbiguousResolution(
                        ambiguous.sourceTerm(), ambiguous.matchedValue(),
                        ambiguous.candidates().stream()
                                .map(candidate -> new ResolutionCandidate(
                                        candidate.dimensionId(), candidate.dimensionName()))
                                .toList()))
                .toList();
    }

    private List<PendingResolution> pendingResolutions(
            QueryAction action, QueryAction currentAction, QuerySemanticIntent semanticIntent,
            RetrievedMetadata retrievedMetadata, NormalizationResult normalization) {
        List<PendingResolution> pending = new java.util.ArrayList<>();
        Set<String> trustedRuleFields = new java.util.LinkedHashSet<>();
        if (normalization != null) {
            trustedRuleFields.addAll(normalization.enforcedMetricIds());
            normalization.enforcedFilters().stream()
                    .map(filter -> filter.dimensionId())
                    .forEach(trustedRuleFields::add);
        }
        addWeakFieldResolutions(
                pending, "度量", action.metricIds().stream()
                        .filter(id -> !currentAction.metricIds().contains(id)).toList(),
                semanticIntent.metricTerms(),
                retrievedMetadata, trustedRuleFields);
        addWeakFieldResolutions(
                pending, "分组维度", action.dimensionIds().stream()
                        .filter(id -> !currentAction.dimensionIds().contains(id)).toList(),
                semanticIntent.groupTerms(),
                retrievedMetadata, trustedRuleFields);
        for (int index = 0; index < action.dimensionFilters().size(); index++) {
            DimensionFilter filter = action.dimensionFilters().get(index);
            if (currentAction.dimensionFilters().contains(filter)
                    || isTimeDimension(filter.dimensionId())
                    || trustedRuleFields.contains(filter.dimensionId())) {
                continue;
            }
            // A final filter must be traceable to one of the user's semantic
            // terms, but the mapping model is allowed to omit unresolved terms
            // (for example, "DP") or reorder filters.  Do not use a positional
            // fallback here: it previously leaked the internal label “筛选维度”
            // into the user-facing confirmation message.
            //
            // RAG is supplementary evidence.  Once the value is explicitly
            // present in the user semantic intent, the normal whole-query
            // confirmation gate is sufficient; an absent retrieval hit must not
            // create a second, misleading confirmation item.
            FilterTerm source = sourceFilterTerm(filter, semanticIntent.filterTerms());
            if (source != null || hasExactValueEvidence(filter, retrievedMetadata)
                    || hasFieldEvidence(filter.dimensionId(),
                            List.of(QueryMetadataCatalog.displayName(filter.dimensionId())), retrievedMetadata)) {
                continue;
            }
            // This is a model-added filter with neither a user semantic source
            // nor retrieval evidence. Keep the existing whole-query confirmation
            // behavior, but describe the condition with a catalog name instead
            // of leaking an internal slot label.
            pending.add(new PendingResolution(
                    "筛选条件", QueryMetadataCatalog.displayName(filter.dimensionId()),
                    filter.dimensionId(), QueryMetadataCatalog.displayName(filter.dimensionId()),
                    "未能关联到本轮输入或检索证据，请确认是否保留该条件"));
        }
        for (int index = 0; index < action.sorts().size(); index++) {
            SortSpec sort = action.sorts().get(index);
            if (currentAction.sorts().contains(sort)) continue;
            String term = bestFieldTerm(
                    sort.fieldId(),
                    semanticIntent.sortTerms().stream().map(QuerySemanticIntent.SortTerm::fieldTerm).toList(),
                    semanticIntent.sortTerms().size() == action.sorts().size() ? index : -1,
                    "排序字段");
            if (!hasFieldEvidence(sort.fieldId(), evidenceTerms(term), retrievedMetadata)) {
                pending.add(new PendingResolution(
                        "排序字段", term, sort.fieldId(), QueryMetadataCatalog.displayName(sort.fieldId()),
                        "本次 RAG 未召回该字段，请确认是否按此字段排序"));
            }
        }
        return List.copyOf(pending);
    }

    private void addWeakFieldResolutions(
            List<PendingResolution> pending, String type, List<String> fieldIds,
            List<String> terms, RetrievedMetadata retrievedMetadata, Set<String> trustedRuleFields) {
        for (int index = 0; index < fieldIds.size(); index++) {
            String fieldId = fieldIds.get(index);
            if (trustedRuleFields.contains(fieldId)) continue;
            String term = bestFieldTerm(
                    fieldId, terms, terms.size() == fieldIds.size() ? index : -1, type);
            if (!hasFieldEvidence(fieldId, evidenceTerms(term), retrievedMetadata)) {
                pending.add(new PendingResolution(
                        type, term, fieldId, QueryMetadataCatalog.displayName(fieldId),
                        "本次 RAG 未召回该字段，请确认是否按此字段查询"));
            }
        }
    }

    private boolean hasFieldEvidence(
            String fieldId, List<String> terms, RetrievedMetadata retrievedMetadata) {
        if (retrievedMetadata != null
                && ((QueryMetadataCatalog.isMetric(fieldId) && retrievedMetadata.metrics().stream()
                        .anyMatch(candidate -> fieldId.equals(candidate.fieldId())))
                    || (QueryMetadataCatalog.isDimension(fieldId) && retrievedMetadata.dimensions().stream()
                        .anyMatch(candidate -> fieldId.equals(candidate.fieldId())))
                    || (QueryMetadataCatalog.isDimension(fieldId) && retrievedMetadata.values().stream()
                        .anyMatch(candidate -> fieldId.equals(candidate.fieldId()))))) {
            return true;
        }
        String displayName = normalizeTerm(QueryMetadataCatalog.displayName(fieldId));
        return terms.stream().filter(StringUtils::hasText)
                .map(this::normalizeTerm)
                .anyMatch(term -> term.equals(displayName)
                        || (term.length() >= 2 && displayName.contains(term))
                        || (displayName.length() >= 2 && term.contains(displayName)));
    }

    private List<String> evidenceTerms(String term) {
        return StringUtils.hasText(term) ? List.of(term) : List.of();
    }

    /**
     * Resolves the user-authored semantic term that produced a final filter.
     * The result is intentionally kept local until the query contract grows a
     * dedicated provenance field; matching by field name and values keeps the
     * association stable even when unresolved terms are dropped or reordered.
     */
    private FilterTerm sourceFilterTerm(DimensionFilter filter, List<FilterTerm> terms) {
        if (terms == null || terms.isEmpty()) return null;
        String displayName = normalizeTerm(QueryMetadataCatalog.displayName(filter.dimensionId()));
        List<FilterTerm> fieldMatches = terms.stream()
                .filter(term -> sameOrContains(normalizeTerm(term.dimensionTerm()), displayName))
                .toList();
        if (fieldMatches.size() == 1) return fieldMatches.get(0);

        List<FilterTerm> valueMatches = terms.stream()
                .filter(term -> sameFilterValues(filter.values(), term.values()))
                .toList();
        if (valueMatches.size() == 1) return valueMatches.get(0);

        return null;
    }

    private boolean sameOrContains(String left, String right) {
        return !left.isBlank() && !right.isBlank()
                && (left.equals(right) || left.contains(right) || right.contains(left));
    }

    private boolean sameFilterValues(List<String> filterValues, List<String> termValues) {
        if (filterValues == null || termValues == null || filterValues.isEmpty() || termValues.isEmpty()) {
            return false;
        }
        Set<String> expected = filterValues.stream()
                .filter(StringUtils::hasText)
                .map(this::normalizeTerm)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> actual = termValues.stream()
                .filter(StringUtils::hasText)
                .map(this::normalizeTerm)
                .collect(java.util.stream.Collectors.toSet());
        return !expected.isEmpty() && expected.equals(actual);
    }

    private QueryAction retainSupportedGeneratedFilters(
            QueryAction action, QueryAction currentAction, QuerySemanticIntent semanticIntent,
            RetrievedMetadata retrievedMetadata) {
        Set<String> explicitValues = semanticIntent.filterTerms().stream()
                .flatMap(filter -> filter.values().stream())
                .filter(StringUtils::hasText)
                .map(this::normalizeTerm)
                .collect(java.util.stream.Collectors.toSet());
        List<DimensionFilter> supported = action.dimensionFilters().stream()
                .filter(filter -> isTimeDimension(filter.dimensionId())
                        || currentAction.dimensionFilters().contains(filter)
                        || filter.values().stream().allMatch(value ->
                                explicitValues.contains(normalizeTerm(value)))
                        || hasExactValueEvidence(filter, retrievedMetadata))
                .toList();
        return supported.size() == action.dimensionFilters().size()
                ? action
                : new QueryAction(action.metricIds(), action.dimensionIds(), supported, action.sorts());
    }

    private boolean hasExactValueEvidence(
            DimensionFilter filter, RetrievedMetadata retrievedMetadata) {
        if (retrievedMetadata == null) {
            return false;
        }
        return filter.values().stream().allMatch(value -> retrievedMetadata.values().stream()
                .anyMatch(candidate -> filter.dimensionId().equals(candidate.fieldId())
                        && normalizeTerm(value).equals(normalizeTerm(candidate.value()))));
    }

    private QueryAction reconcileGroundings(QueryAction action, GroundingResult grounding) {
        GroundingResult source = grounding == null ? GroundingResult.empty() : grounding;
        List<DimensionFilter> filters = new java.util.ArrayList<>(action.dimensionFilters());
        for (AmbiguousGrounding ambiguous : source.ambiguousFilters()) {
            List<DimensionFilter> matches = filters.stream()
                    .filter(filter -> ambiguous.dimensionIds().contains(filter.dimensionId())
                            && hasValue(filter, ambiguous.matchedValue()))
                    .toList();
            if (matches.size() > 1) {
                filters.removeAll(matches);
            }
        }
        for (GroundedFilter required : source.requiredFilters()) {
            boolean exact = filters.stream().anyMatch(filter ->
                    required.dimensionId().equals(filter.dimensionId())
                            && hasValue(filter, required.matchedValue()));
            boolean conflicting = filters.stream().anyMatch(filter ->
                    required.dimensionId().equals(filter.dimensionId())
                            && !hasValue(filter, required.matchedValue()));
            if (!exact && !conflicting) {
                filters.add(new DimensionFilter(
                        required.dimensionId(), "EQUALS", List.of(required.matchedValue())));
            }
        }
        return new QueryAction(action.metricIds(), action.dimensionIds(), List.copyOf(filters), action.sorts());
    }

    private void applyGroundingCoverage(
            QueryAction action, GroundingResult grounding, List<String> unresolvedItems) {
        GroundingResult source = grounding == null ? GroundingResult.empty() : grounding;
        for (GroundedFilter required : source.requiredFilters()) {
            boolean covered = action.dimensionFilters().stream().anyMatch(filter ->
                    required.dimensionId().equals(filter.dimensionId())
                            && hasValue(filter, required.matchedValue()));
            updateCoverage(
                    unresolvedItems,
                    required.sourceTerm(),
                    required.matchedValue(),
                    required.dimensionId(),
                    required.dimensionName(),
                    covered);
        }
        for (AmbiguousGrounding ambiguous : source.ambiguousFilters()) {
            List<DimensionFilter> matches = action.dimensionFilters().stream()
                    .filter(filter -> ambiguous.dimensionIds().contains(filter.dimensionId())
                            && hasValue(filter, ambiguous.matchedValue()))
                    .toList();
            if (matches.size() == 1) {
                DimensionFilter selected = matches.get(0);
                var candidate = ambiguous.candidates().stream()
                        .filter(item -> selected.dimensionId().equals(item.dimensionId()))
                        .findFirst().orElse(null);
                updateCoverage(
                        unresolvedItems,
                        ambiguous.sourceTerm(),
                        ambiguous.matchedValue(),
                        selected.dimensionId(),
                        candidate == null ? "" : candidate.dimensionName(),
                        true);
            } else {
                updateCoverage(
                        unresolvedItems,
                        ambiguous.sourceTerm(),
                        ambiguous.matchedValue(),
                        "",
                        "",
                        false);
            }
        }
    }

    private void updateCoverage(
            List<String> unresolvedItems,
            String sourceTerm,
            String matchedValue,
            String dimensionId,
            String dimensionName,
            boolean covered) {
        if (!StringUtils.hasText(sourceTerm)) {
            return;
        }
        if (covered) {
            unresolvedItems.removeIf(item -> resolvedItemMatches(
                    item, sourceTerm, matchedValue, dimensionId, dimensionName));
        } else if (unresolvedItems.stream()
                .noneMatch(item -> normalizeTerm(item).equals(normalizeTerm(sourceTerm)))) {
            unresolvedItems.add(sourceTerm);
        }
    }

    private boolean resolvedItemMatches(
            String unresolvedItem,
            String sourceTerm,
            String matchedValue,
            String dimensionId,
            String dimensionName) {
        String item = normalizeTerm(unresolvedItem);
        String source = normalizeTerm(sourceTerm);
        String value = normalizeTerm(matchedValue);
        String fieldId = normalizeTerm(dimensionId);
        String fieldName = normalizeTerm(dimensionName);
        if (item.equals(source) || item.equals(value)
                || (!fieldId.isBlank() && item.equals(fieldId))
                || (!fieldName.isBlank() && item.equals(fieldName))) {
            return true;
        }
        boolean carriesResolvedValue = !value.isBlank() && item.contains(value);
        return carriesResolvedValue
                && ((!source.isBlank() && item.contains(source))
                        || (!fieldId.isBlank() && item.contains(fieldId))
                        || (!fieldName.isBlank() && item.contains(fieldName)));
    }

    /** Prevents a final query state from simultaneously applying and rejecting the same filter. */
    private void removeItemsCoveredByFinalFilters(
            QueryAction action, List<String> unresolvedItems) {
        for (DimensionFilter filter : action.dimensionFilters()) {
            String fieldId = normalizeTerm(filter.dimensionId());
            String fieldName = normalizeTerm(QueryMetadataCatalog.displayName(filter.dimensionId()));
            List<String> values = filter.values().stream().map(this::normalizeTerm).toList();
            unresolvedItems.removeIf(item -> {
                String normalizedItem = normalizeTerm(item);
                if (normalizedItem.equals(fieldId) || normalizedItem.equals(fieldName)) {
                    return true;
                }
                boolean namesField = normalizedItem.contains(fieldId)
                        || (!fieldName.isBlank() && normalizedItem.contains(fieldName));
                return namesField && values.stream()
                        .filter(value -> !value.isBlank())
                        .anyMatch(normalizedItem::contains);
            });
        }
    }

    private boolean hasValue(DimensionFilter filter, String value) {
        return filter.values().stream()
                .anyMatch(item -> normalizeTerm(item).equals(normalizeTerm(value)));
    }

    private QuerySemanticIntent sanitizeSearchTerms(
            QuerySemanticIntent intent, String message, String pendingQueryIntent) {
        String allowedSource = (message == null ? "" : message) + "\n"
                + (pendingQueryIntent == null ? "" : pendingQueryIntent);
        String normalizedSource = allowedSource.toLowerCase(java.util.Locale.ROOT);
        List<QuerySemanticIntent.SearchTerm> validSearchTerms = new java.util.ArrayList<>();
        for (QuerySemanticIntent.SearchTerm searchTerm : intent.searchTerms()) {
            if (searchTerm == null || !StringUtils.hasText(searchTerm.text())) continue;
            String text = searchTerm.text().trim();
            if (!normalizedSource.contains(text.toLowerCase(java.util.Locale.ROOT))) continue;
            QuerySemanticIntent.SearchTerm normalized = new QuerySemanticIntent.SearchTerm(
                    text, searchTerm.context() == null ? "" : searchTerm.context().trim());
            if (!validSearchTerms.contains(normalized)) validSearchTerms.add(normalized);
        }
        return new QuerySemanticIntent(
                validSearchTerms, intent.metricTerms(), intent.groupTerms(), intent.filterTerms(),
                intent.sortTerms(), intent.unmappedTerms());
    }

    /**
     * Treats the previous query context as sticky state. The LLM may still emit a
     * complete QueryAction, but only slots touched by the latest turn are allowed
     * to replace existing values. This keeps already-grounded fields out of the
     * current turn's RAG evidence requirements.
     */
    private QueryAction mergeStickyState(
            QueryAction current, QueryAction proposed, QuerySemanticIntent intent,
            RetrievedMetadata metadata, GroundingResult grounding,
            NormalizationResult normalization, String message) {
        if (current == null || isEmpty(current)) return proposed;

        boolean additive = containsAny(message, "增加", "添加", "再加", "同时", "还要", "以及");
        boolean replacement = containsAny(message, "改成", "改为", "换成", "替换", "只看", "仅看");
        boolean removal = containsAny(message, "去掉", "删除", "不要", "不看", "取消");

        List<String> metrics = current.metricIds();
        boolean metricsTouched = !intent.metricTerms().isEmpty()
                || (normalization != null && !normalization.enforcedMetricIds().isEmpty());
        if (metricsTouched) {
            metrics = additive && !replacement && !removal
                    ? union(current.metricIds(), proposed.metricIds())
                    : proposed.metricIds();
        }

        List<String> dimensions = current.dimensionIds();
        if (!intent.groupTerms().isEmpty()) {
            dimensions = additive && !replacement && !removal
                    ? union(current.dimensionIds(), proposed.dimensionIds())
                    : proposed.dimensionIds();
        }

        Set<String> touchedFilterFields = touchedFilterFields(metadata, grounding, normalization);
        List<DimensionFilter> filters = new java.util.ArrayList<>(current.dimensionFilters());
        for (DimensionFilter candidate : proposed.dimensionFilters()) {
            boolean unchanged = current.dimensionFilters().contains(candidate);
            boolean latestTurnEvidence = touchedFilterFields.contains(candidate.dimensionId())
                    || mentionsField(normalizeTerm(message), candidate.dimensionId())
                    || (isTimeDimension(candidate.dimensionId()) && !intent.filterTerms().isEmpty());
            if (!unchanged && latestTurnEvidence) {
                filters.removeIf(existing -> existing.dimensionId().equals(candidate.dimensionId()));
                filters.add(candidate);
            }
        }
        if (removal) {
            String normalizedMessage = normalizeTerm(message);
            filters.removeIf(existing -> !proposed.dimensionFilters().stream()
                    .anyMatch(candidate -> candidate.dimensionId().equals(existing.dimensionId()))
                    && (touchedFilterFields.contains(existing.dimensionId())
                            || mentionsField(normalizedMessage, existing.dimensionId()))
                    && mentionsFilter(normalizedMessage, existing));
        }

        List<SortSpec> sorts = current.sorts();
        if (!intent.sortTerms().isEmpty()) {
            sorts = proposed.sorts();
        } else if (containsAny(message, "不排序", "取消排序", "清空排序")) {
            sorts = List.of();
        }
        return new QueryAction(metrics, dimensions, List.copyOf(filters), sorts);
    }

    private Set<String> touchedFilterFields(
            RetrievedMetadata metadata, GroundingResult grounding, NormalizationResult normalization) {
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        if (metadata != null) {
            metadata.values().stream().map(candidate -> candidate.fieldId()).forEach(fields::add);
        }
        GroundingResult evidence = grounding == null ? GroundingResult.empty() : grounding;
        evidence.requiredFilters().stream().map(GroundedFilter::dimensionId).forEach(fields::add);
        evidence.ambiguousFilters().stream()
                .flatMap(item -> item.dimensionIds().stream()).forEach(fields::add);
        if (normalization != null) {
            normalization.enforcedFilters().stream()
                    .map(filter -> filter.dimensionId()).forEach(fields::add);
        }
        return Set.copyOf(fields);
    }

    private boolean mentionsFilter(String normalizedMessage, DimensionFilter filter) {
        if (mentionsField(normalizedMessage, filter.dimensionId())) {
            return true;
        }
        return filter.values().stream()
                .map(this::normalizeTerm)
                .filter(value -> !value.isBlank())
                .anyMatch(normalizedMessage::contains);
    }

    private boolean mentionsField(String normalizedMessage, String fieldId) {
        return normalizedMessage.contains(normalizeTerm(fieldId))
                || normalizedMessage.contains(normalizeTerm(QueryMetadataCatalog.displayName(fieldId)));
    }

    private List<String> union(List<String> current, List<String> proposed) {
        LinkedHashSet<String> values = new LinkedHashSet<>(current);
        values.addAll(proposed);
        return List.copyOf(values);
    }

    private boolean isEmpty(QueryAction action) {
        return action.metricIds().isEmpty() && action.dimensionIds().isEmpty()
                && action.dimensionFilters().isEmpty() && action.sorts().isEmpty();
    }

    private boolean containsAny(String message, String... terms) {
        String source = message == null ? "" : message;
        for (String term : terms) if (source.contains(term)) return true;
        return false;
    }

    private QueryAction applyRuleConstraints(QueryAction source, NormalizationResult normalization) {
        if (normalization == null || normalization.appliedRules().isEmpty()) return source;
        LinkedHashSet<String> metrics = new LinkedHashSet<>(source.metricIds());
        metrics.addAll(normalization.enforcedMetricIds());
        List<DimensionFilter> filters = new java.util.ArrayList<>(source.dimensionFilters());
        for (var target : normalization.enforcedFilters()) {
            filters.removeIf(existing -> existing.dimensionId().equals(target.dimensionId()));
            filters.add(new DimensionFilter(target.dimensionId(), target.operator(), target.values()));
        }
        return new QueryAction(List.copyOf(metrics), source.dimensionIds(), List.copyOf(filters), source.sorts());
    }

    private String normalizeTerm(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[\\s，,。；;：:（）()\"']+", "");
    }

    private List<String> textList(JsonNode node, String fieldName) {
        if (!node.isArray()) {
            throw new IllegalArgumentException(fieldName + " 必须是数组");
        }
        List<String> values = new java.util.ArrayList<>();
        for (JsonNode item : node) {
            String value = item.isTextual() ? item.asText().trim() : "";
            if (value.isBlank()) {
                throw new IllegalArgumentException(fieldName + " 只能包含非空文本");
            }
            if (!values.contains(value)) {
                values.add(value);
            }
        }
        return List.copyOf(values);
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

    private record ParsedQueryAction(
            QueryAction action, String explanation, List<String> unresolvedItems,
            List<PendingResolution> pendingResolutions,
            List<AmbiguousResolution> ambiguousResolutions) {
    }

    public record QueryActionResult(
            QueryAction action, String explanation, LlmResultMessage llmMessage,
            String semanticIntent, List<String> unresolvedItems, List<PendingResolution> pendingResolutions,
            List<AppliedSemanticRule> appliedSemanticRules,
            List<AmbiguousResolution> ambiguousResolutions) {
        public QueryActionResult {
            explanation = explanation == null ? "" : explanation.trim();
            semanticIntent = semanticIntent == null ? "" : semanticIntent.trim();
            unresolvedItems = unresolvedItems == null ? List.of() : List.copyOf(unresolvedItems);
            pendingResolutions = pendingResolutions == null ? List.of() : List.copyOf(pendingResolutions);
            appliedSemanticRules = appliedSemanticRules == null ? List.of() : List.copyOf(appliedSemanticRules);
            ambiguousResolutions = ambiguousResolutions == null ? List.of() : List.copyOf(ambiguousResolutions);
        }

        public QueryActionResult(
                QueryAction action, String explanation, LlmResultMessage llmMessage,
                String semanticIntent, List<String> unresolvedItems,
                List<PendingResolution> pendingResolutions) {
            this(action, explanation, llmMessage, semanticIntent, unresolvedItems, pendingResolutions,
                    List.of(), List.of());
        }

        public QueryActionResult(
                QueryAction action, String explanation, LlmResultMessage llmMessage,
                String semanticIntent, List<String> unresolvedItems,
                List<PendingResolution> pendingResolutions,
                List<AppliedSemanticRule> appliedSemanticRules) {
            this(action, explanation, llmMessage, semanticIntent, unresolvedItems, pendingResolutions,
                    appliedSemanticRules, List.of());
        }
    }

    private String bestFieldTerm(String fieldId, List<String> terms, int fallbackIndex, String fallback) {
        String displayName = normalizeTerm(QueryMetadataCatalog.displayName(fieldId));
        for (String term : terms) {
            String normalized = normalizeTerm(term);
            if (!normalized.isBlank()
                    && (normalized.equals(displayName)
                            || normalized.contains(displayName)
                            || displayName.contains(normalized))) {
                return term;
            }
        }
        return fallbackIndex >= 0 && fallbackIndex < terms.size() && StringUtils.hasText(terms.get(fallbackIndex))
                ? terms.get(fallbackIndex) : fallback;
    }

    /** A catalog-valid mapping that needs explicit user approval because retrieval did not evidence it. */
    public record PendingResolution(
            String type, String originalTerm, String fieldId, String fieldName, String reason)
            implements Serializable {
    }

    /** Multiple exact value owners remain after mapping and require an explicit user choice. */
    public record AmbiguousResolution(
            String originalTerm, String value, List<ResolutionCandidate> candidates)
            implements Serializable {
        public AmbiguousResolution {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    public record ResolutionCandidate(String fieldId, String fieldName) implements Serializable {
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
