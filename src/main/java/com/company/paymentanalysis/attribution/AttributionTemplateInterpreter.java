package com.company.paymentanalysis.attribution;

import com.company.paymentanalysis.attribution.AttributionCatalog.AttributionDimension;
import com.company.paymentanalysis.attribution.AttributionTemplateModels.DimensionLayer;
import com.company.paymentanalysis.attribution.AttributionTemplateModels.DimensionSelection;
import com.company.paymentanalysis.attribution.AttributionTemplateModels.DimensionTemplate;
import com.company.paymentanalysis.attribution.AttributionTemplateModels.MappingIssue;
import com.company.paymentanalysis.attribution.AttributionTemplateModels.TemplateChatRequest;
import com.company.paymentanalysis.attribution.AttributionTemplateModels.TemplateConversationMessage;
import com.company.paymentanalysis.attribution.AttributionTemplateModels.TemplateChatResponse;
import com.company.paymentanalysis.attribution.AttributionTemplateModels.TemplateFilter;
import com.company.paymentanalysis.chat.ClarificationPlanner;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient.ChatMessage;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient.LlmResultMessage;
import com.company.paymentanalysis.time.RelativeTimeResolver;
import com.company.paymentanalysis.ragflow.MetadataRetrievalTool;
import com.company.paymentanalysis.ragflow.MetadataRetrievalTool.RetrievedMetadata;
import com.company.paymentanalysis.ragflow.RetrievedMetadataPrompt;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.time.YearMonth;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Two-pass natural-language interpreter for complete attribution dimension templates. */
@Component
public class AttributionTemplateInterpreter {

    private static final Set<String> MODES = Set.of("AUTO", "USER_DEFINED", "HYBRID");
    private static final Set<String> CONTINUATIONS = Set.of("AUTO", "STOP");
    private static final Set<String> CONFIDENCES = Set.of("HIGH", "MEDIUM", "LOW");
    private static final Set<String> FILTER_OPERATORS = Set.of(
            "EQUALS", "IN", "NOT_EQUALS", "NOT_IN", "GREATER", "GREATER_EQUALS",
            "LESS", "LESS_EQUALS", "BETWEEN", "CONTAINS");

    private final OpenAiCompatibleLlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final RelativeTimeResolver relativeTimeResolver;
    private final MetadataRetrievalTool metadataRetrievalTool;
    private final ClarificationPlanner clarificationPlanner;

    @Autowired
    public AttributionTemplateInterpreter(
            OpenAiCompatibleLlmClient llmClient, ObjectMapper objectMapper, Clock clock,
            MetadataRetrievalTool metadataRetrievalTool, ClarificationPlanner clarificationPlanner) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.relativeTimeResolver = new RelativeTimeResolver(clock);
        this.metadataRetrievalTool = metadataRetrievalTool;
        this.clarificationPlanner = clarificationPlanner;
    }

    AttributionTemplateInterpreter(
            OpenAiCompatibleLlmClient llmClient, ObjectMapper objectMapper, Clock clock,
            MetadataRetrievalTool metadataRetrievalTool) {
        this(llmClient, objectMapper, clock, metadataRetrievalTool, ClarificationPlanner.noOp());
    }

    AttributionTemplateInterpreter(
            OpenAiCompatibleLlmClient llmClient, ObjectMapper objectMapper, Clock clock) {
        this(llmClient, objectMapper, clock, MetadataRetrievalTool.noOp());
    }

    AttributionTemplateInterpreter(OpenAiCompatibleLlmClient llmClient, ObjectMapper objectMapper) {
        this(llmClient, objectMapper, Clock.systemDefaultZone());
    }

    public TemplateChatResponse interpret(TemplateChatRequest request) {
        try {
            DimensionTemplate current = request.currentTemplate() == null
                    ? DimensionTemplate.auto()
                    : validateTemplate(request.currentTemplate(), "DRAFT");
            List<ChatMessage> intentMessages = List.of(
                    new ChatMessage("system", intentPrompt()),
                    new ChatMessage("user", intentPayload(request, current)));
            String mockIntent = "{\"analysisTerms\":[],\"explicitOrder\":false,\"requestsAutoExploration\":true,"
                    + "\"requestsStopAfterTemplate\":false,\"metricTerm\":\"\",\"currentPeriod\":\"\","
                    + "\"comparisonPeriod\":\"\",\"filterTerms\":[],\"unmappedTerms\":[]}";
            LlmResultMessage intentMessage = complete(intentMessages, mockIntent, request.model());
            RawSemanticIntent semanticIntent = parseSemanticIntent(intentMessage.content());
            RelativeTimeResolver.ResolvedPeriodPair resolvedPeriods = resolveAuthoritativePeriods(request, current);
            semanticIntent = semanticIntent.withAuthoritativePeriods(resolvedPeriods);
            RetrievedMetadata retrievedMetadata = metadataRetrievalTool.retrieveForAttribution(
                    request.message(), objectMapper.writeValueAsString(semanticIntent));

            List<ChatMessage> mappingMessages = List.of(
                    new ChatMessage("system", mappingPrompt(retrievedMetadata)),
                    new ChatMessage("user", mappingPayload(request.message(), current,
                            objectMapper.writeValueAsString(semanticIntent))));
            String mockMapped = objectMapper.writeValueAsString(new RawMappedTemplate(
                    "自由探索", "AUTO", "", "", "", List.of(), List.of(), "AUTO",
                    "未识别到明确分析维度，保持自由探索。", List.of(), List.of()));
            LlmResultMessage mappingMessage = complete(mappingMessages, mockMapped, request.model());
            RawMappedTemplate mapped = parseMappedTemplate(mappingMessage.content());
            mapped = mapped.withAuthoritativePeriods(resolvedPeriods).withValidPeriods();
            DimensionTemplate template = validateMapped(mapped);
            String status = hasRequiredAnalysisInputs(template)
                    ? "READY_TO_CONFIRM"
                    : "NEEDS_CLARIFICATION";
            String reply = reply(request, current, template, mapped, retrievedMetadata, status);
            return new TemplateChatResponse(
                    status, reply, template, mapped.unmappedTerms(), mapped.mappingIssues(),
                    intentMessage, mappingMessage);
        } catch (JsonProcessingException | RuntimeException exception) {
            throw new IllegalArgumentException("分析层级模板解析失败：" + conciseMessage(exception), exception);
        }
    }

    /**
     * Periods are never accepted from the model alone. Use user-authored turns
     * first, then preserve an existing template; an empty result remains empty
     * and is surfaced as a clarification instead of becoming a guessed date.
     */
    private RelativeTimeResolver.ResolvedPeriodPair resolveAuthoritativePeriods(
            TemplateChatRequest request, DimensionTemplate current) {
        StringBuilder userText = new StringBuilder();
        for (TemplateConversationMessage message : request.conversationHistory()) {
            if (message != null && "user".equals(message.role()) && StringUtils.hasText(message.text())) {
                userText.append(message.text()).append('\n');
            }
        }
        userText.append(request.message());
        RelativeTimeResolver.ResolvedPeriodPair fromMessage = relativeTimeResolver
                .resolveAttributionPeriods(userText.toString());
        return new RelativeTimeResolver.ResolvedPeriodPair(
                fromMessage.currentPeriod() == null
                        ? validPeriodOrBlank(current.currentPeriod()) : fromMessage.currentPeriod(),
                fromMessage.comparisonPeriod() == null
                        ? validPeriodOrBlank(current.comparisonPeriod()) : fromMessage.comparisonPeriod());
    }

    public DimensionTemplate confirm(DimensionTemplate template) {
        return validateTemplate(template, "CONFIRMED");
    }

    private String intentPrompt() {
        return """
                你是归因分析模板语义理解器。你会同时收到当前完整模板、当前会话的完整历史和用户本轮要求。
                结合这些上下文，理解用户是在上一轮框架上继续修改，并输出用户本轮要求生效后的完整最终语义模板；不选择数据库字段ID，不输出编辑动作。
                只返回JSON，字段必须且只能为：analysisTerms、explicitOrder、requestsAutoExploration、requestsStopAfterTemplate、metricTerm、currentPeriod、comparisonPeriod、filterTerms、unmappedTerms。
                固定结构：
                {"analysisTerms":[{"term":"用户原词","level":1,"context":"为什么把它作为这一层"}],"explicitOrder":true,"requestsAutoExploration":false,"requestsStopAfterTemplate":false,"metricTerm":"用户度量原词","currentPeriod":"yyyy-MM","comparisonPeriod":"yyyy-MM","filterTerms":[{"dimensionTerm":"过滤维度原词","operator":"EQUALS|IN|NOT_EQUALS|NOT_IN|GREATER|GREATER_EQUALS|LESS|LESS_EQUALS|BETWEEN|CONTAINS","values":["用户给出的值"],"context":"过滤条件原句"}],"unmappedTerms":[]}

                规则：
                1. analysisTerms代表本轮修改完成后的全部最终层级，不只是本轮新提到的词。必须包含当前模板中未被用户修改的层级和维度；同一层多个并行概念使用相同level，level从1连续编号。
                2. 当前模板是上一轮已经确认的分析框架，完整对话历史用于消解“它、这层、前两层”等指代；本轮用户要求优先级最高。不要因为本轮没有再次提到某个度量、周期、过滤条件或层级就删除它。
                3. 完整理解条件、假设和判断句。把其中提出的分析对象逐项放入analysisTerms，把限定数据范围的内容放入filterTerms；不能因为一句话含有条件或判断语气而丢弃其中任何分析要求。无法映射的用户原词放入unmappedTerms等待澄清。
                4. 用户表达“继续定位、继续分析、再看”且没有指定层号时，表示在当前最后一层之后继续形成下一层；同一处并列列举的分析对象属于同一层。这里只依据自然语言结构判断层级，不预设任何业务维度名称。
                5. 宽泛或可能歧义的业务词也必须原样进入analysisTerms，由第二阶段结合动态元数据映射或提出澄清；第一阶段不得因为无法确定字段ID而删除它。不要把具体成员值发明成数据库维度。
                6. 用户说“后面你自己探索”时requestsAutoExploration=true；明确说“到这里停止”时requestsStopAfterTemplate=true。
                7. 用户要求自由探索、清空自定义层级且没有指定维度时，analysisTerms=[]且requestsAutoExploration=true。
                8. 永远不输出ADD、MOVE、REMOVE等动作，只输出修改完成后的完整最终语义模板。
                9. 自然语言层级约定：“第N层增加X”表示最终第N层保留原维度并加入X；“新增第N层X”表示插入新层并将原层级顺延；“第N层改为/只有/两个A和B”表示该层最终由用户明确列出的内容组成；“第N层和第M层合并”表示按原顺序合并两层维度、删除后一层并将后续层级连续前移。
                10. metricTerm、currentPeriod、comparisonPeriod也输出修改后的完整最终值。用户未修改时从当前模板保留；允许把2026.5、2026/5、2026年5月统一输出为2026-05。两个不同月份都明确时，较新的放currentPeriod、较旧的放comparisonPeriod；不自行发明用户没有提供的日期。
                11. filterTerms同样输出最终完整过滤范围。用户未修改时保留。过滤条件限定数据范围；用于决定下一步分析方向的条件不属于过滤条件，其中明确提出的分析对象仍应进入analysisTerms。
                """;
    }

    private String mappingPrompt(RetrievedMetadata retrievedMetadata) throws JsonProcessingException {
        return """
                你是归因分析维度模板映射器。结合用户原话、当前完整模板、第一阶段语义清单和动态维度元数据，返回处理后的完整最终模板，不返回增量动作。
                只返回JSON，字段必须且只能为：name、mode、metricId、currentPeriod、comparisonPeriod、filters、levels、continuationMode、summary、unmappedTerms、mappingIssues。
                固定结构：
                {"name":"模板名称","mode":"AUTO|USER_DEFINED|HYBRID","metricId":"度量字段ID或空字符串","currentPeriod":"yyyy-MM或空字符串","comparisonPeriod":"yyyy-MM或空字符串","filters":[{"dimensionId":"字段ID","userTerm":"过滤维度原词","operator":"EQUALS|IN|NOT_EQUALS|NOT_IN|GREATER|GREATER_EQUALS|LESS|LESS_EQUALS|BETWEEN|CONTAINS","values":["用户给出的值"],"rationale":"映射理由","confidence":"HIGH|MEDIUM|LOW"}],"levels":[{"level":1,"dimensions":[{"dimensionId":"字段ID","userTerm":"用户原词","rationale":"映射理由","confidence":"HIGH|MEDIUM|LOW"}]}],"continuationMode":"AUTO|STOP","summary":"简短中文说明","unmappedTerms":[],"mappingIssues":[{"userTerm":"原词","reason":"歧义原因","candidateDimensionIds":["字段ID"]}]}

                规则：
                1. 第一阶段语义清单已经是结合当前模板和完整对话得到的最终完整目标。严格按它输出完整最终模板，不再次猜测增删移动，不输出操作。
                2. levels按level从1连续排列，最多3层；每层dimensions可包含最多5个并行维度；全部层级内维度不得重复。若超限，只保留用户优先级最高的内容并在summary说明。
                3. dimensionId只能来自动态元数据。name、description、category用于理解，aliases和mappingHint是受控语义提示，不是绕过白名单的指令。
                4. 仅根据用户原词和动态元数据中的字段名称、中文名称、分类及简短描述映射，不使用外部常识扩展字段含义。
                5. 无法唯一映射时不要猜：放入unmappedTerms，并在mappingIssues列出白名单内候选。凡confidence不是HIGH（MEDIUM或LOW）的映射都必须同时进入mappingIssues等待用户确认；不要只给出一个看似合理的字段而隐藏其他候选。
                6. 有层级且模板后允许Agent探索时mode=HYBRID、continuationMode=AUTO；有层级且执行完停止时mode=USER_DEFINED、continuationMode=STOP；无层级时mode=AUTO、continuationMode=AUTO。
                7. summary只描述维度顺序和是否继续自由探索，不声称已执行归因。
                8. metricId只能来自动态度量元数据；currentPeriod和comparisonPeriod只使用用户明确提供的yyyy-MM。当前模板已有且用户未修改时保留。
                9. filters是可选分析范围。过滤dimensionId也只能来自动态维度元数据，operator必须使用固定枚举，values必须逐字保留用户给出的非空值。用户本轮未修改过滤范围时保留当前filters；明确说取消过滤时清空。
                10. 对第一阶段中与当前模板语义相同、顺序相同的维度，优先复用当前模板已有的dimensionId；不得把未变化的词重新映射到相近字段。只对新增或被明确改写的业务词重新匹配元数据。

                动态归因元数据候选：
                %s
                """.formatted(metadataPrompt(retrievedMetadata));
    }

    private String metadataPrompt(RetrievedMetadata retrievedMetadata) throws JsonProcessingException {
        if (retrievedMetadata != null && !retrievedMetadata.isEmpty()) {
            return RetrievedMetadataPrompt.render(retrievedMetadata);
        }
        return "可用归因维度：" + objectMapper.writeValueAsString(AttributionCatalog.dimensions())
                + "\n可用归因度量：" + objectMapper.writeValueAsString(AttributionCatalog.metricIds().stream()
                        .map(id -> java.util.Map.of("id", id, "name", AttributionCatalog.metricName(id))).toList());
    }

    private String mappingPayload(String message, DimensionTemplate current, String semanticIntent)
            throws JsonProcessingException {
        return "用户原始输入：" + message
                + "\n当前完整模板：" + objectMapper.writeValueAsString(current)
                + "\n第一阶段语义清单：" + semanticIntent;
    }

    private String intentPayload(TemplateChatRequest request, DimensionTemplate current)
            throws JsonProcessingException {
        return "当前完整模板：" + objectMapper.writeValueAsString(current)
                + "\n当前会话完整历史：" + objectMapper.writeValueAsString(request.conversationHistory())
                + "\n用户本轮要求：" + request.message();
    }

    private DimensionTemplate validateMapped(RawMappedTemplate raw) {
        for (MappingIssue issue : raw.mappingIssues()) {
            if (issue == null || !StringUtils.hasText(issue.userTerm()) || !StringUtils.hasText(issue.reason())
                    || issue.candidateDimensionIds().stream().anyMatch(id -> !AttributionCatalog.isDimension(id))) {
                throw new IllegalArgumentException("mappingIssues包含不合法的歧义映射");
            }
        }
        String metricName = StringUtils.hasText(raw.metricId()) && AttributionCatalog.isMetric(raw.metricId())
                ? AttributionCatalog.metricName(raw.metricId()) : "";
        DimensionTemplate template = new DimensionTemplate(
                raw.name(), raw.mode(), raw.metricId(), metricName, raw.currentPeriod(), raw.comparisonPeriod(),
                raw.filters().stream().map(item -> new TemplateFilter(
                        item.dimensionId(), AttributionCatalog.dimension(item.dimensionId()).name(),
                        item.userTerm(), item.operator(), item.values(), item.rationale(), item.confidence())).toList(),
                raw.levels().stream().map(layer -> new DimensionLayer(layer.level(), layer.dimensions().stream()
                        .map(item -> new DimensionSelection(
                                item.dimensionId(), AttributionCatalog.dimension(item.dimensionId()).name(),
                                item.userTerm(), item.rationale(), AttributionCatalog.dimension(item.dimensionId()).mappingHint(),
                                item.confidence())).toList())).toList(),
                raw.continuationMode(), "DRAFT", raw.summary());
        return validateTemplate(template, "DRAFT");
    }

    private DimensionTemplate validateTemplate(DimensionTemplate template, String status) {
        if (template == null || !MODES.contains(template.mode())
                || !CONTINUATIONS.contains(template.continuationMode())) {
            throw new IllegalArgumentException("模板模式或后续策略不合法");
        }
        if (StringUtils.hasText(template.metricId()) && !AttributionCatalog.isMetric(template.metricId())) {
            throw new IllegalArgumentException("模板度量必须来自归因度量白名单");
        }
        String currentPeriod = normalizePeriod(template.currentPeriod());
        String comparisonPeriod = normalizePeriod(template.comparisonPeriod());
        validatePeriod(currentPeriod, "当前周期");
        validatePeriod(comparisonPeriod, "对比周期");
        if (StringUtils.hasText(currentPeriod) && StringUtils.hasText(comparisonPeriod)
                && YearMonth.parse(currentPeriod).isBefore(YearMonth.parse(comparisonPeriod))) {
            String older = currentPeriod;
            currentPeriod = comparisonPeriod;
            comparisonPeriod = older;
        }
        java.util.ArrayList<TemplateFilter> normalizedFilters = new java.util.ArrayList<>();
        for (TemplateFilter filter : template.filters()) {
            String operator = filter == null || filter.operator() == null ? "" : filter.operator().toUpperCase();
            if (filter == null || !AttributionCatalog.isDimension(filter.dimensionId())
                    || !FILTER_OPERATORS.contains(operator) || filter.values().isEmpty()
                    || filter.values().stream().anyMatch(value -> !StringUtils.hasText(value))
                    || !CONFIDENCES.contains(filter.confidence())) {
                throw new IllegalArgumentException("过滤条件必须使用归因维度、合法操作符和非空值");
            }
            if ("BETWEEN".equals(operator) && filter.values().size() != 2) {
                throw new IllegalArgumentException("BETWEEN过滤必须包含两个边界值");
            }
            AttributionDimension definition = AttributionCatalog.dimension(filter.dimensionId());
            normalizedFilters.add(new TemplateFilter(
                    filter.dimensionId(), definition.name(), safeText(filter.userTerm()), operator,
                    filter.values().stream().map(String::trim).toList(), safeText(filter.rationale()), filter.confidence()));
        }
        if (template.levels().size() > 3) {
            throw new IllegalArgumentException("分析层级最多3层");
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        java.util.ArrayList<DimensionLayer> normalized = new java.util.ArrayList<>();
        for (int index = 0; index < template.levels().size(); index++) {
            DimensionLayer layer = template.levels().get(index);
            if (layer.level() != index + 1 || layer.dimensions().isEmpty() || layer.dimensions().size() > 5) {
                throw new IllegalArgumentException("分析层级必须连续且每层包含1至5个维度");
            }
            java.util.ArrayList<DimensionSelection> dimensions = new java.util.ArrayList<>();
            for (DimensionSelection item : layer.dimensions()) {
                if (item == null || !AttributionCatalog.isDimension(item.dimensionId())
                        || !ids.add(item.dimensionId()) || !CONFIDENCES.contains(item.confidence())) {
                    throw new IllegalArgumentException("分析维度必须唯一且来自归因维度白名单");
                }
                AttributionDimension definition = AttributionCatalog.dimension(item.dimensionId());
                dimensions.add(new DimensionSelection(
                        item.dimensionId(), definition.name(), safeText(item.userTerm()), safeText(item.rationale()),
                        definition.mappingHint(), item.confidence()));
            }
            normalized.add(new DimensionLayer(layer.level(), List.copyOf(dimensions)));
        }
        if (template.levels().isEmpty() && !"AUTO".equals(template.mode())) {
            throw new IllegalArgumentException("空模板只能使用AUTO模式");
        }
        if (!template.levels().isEmpty() && "AUTO".equals(template.mode())) {
            throw new IllegalArgumentException("包含自定义层级的模板不能使用AUTO模式");
        }
        String name = StringUtils.hasText(template.name()) ? template.name().trim() : "未命名分析模板";
        String summary = StringUtils.hasText(template.summary()) ? template.summary().trim() : "已生成分析维度层级";
        String metricId = safeText(template.metricId());
        String metricName = metricId.isEmpty() ? "" : AttributionCatalog.metricName(metricId);
        DimensionTemplate result = new DimensionTemplate(
                name, template.mode(), metricId, metricName, currentPeriod,
                comparisonPeriod, List.copyOf(normalizedFilters), List.copyOf(normalized),
                template.continuationMode(), status, summary);
        if ("CONFIRMED".equals(status) && !hasRequiredAnalysisInputs(result)) {
            throw new IllegalArgumentException("确认模板前必须设置度量、当前周期和对比周期");
        }
        return result;
    }

    private boolean hasRequiredAnalysisInputs(DimensionTemplate template) {
        if (!StringUtils.hasText(template.metricId()) || !StringUtils.hasText(template.currentPeriod())
                || !StringUtils.hasText(template.comparisonPeriod())) return false;
        return YearMonth.parse(template.currentPeriod()).isAfter(YearMonth.parse(template.comparisonPeriod()));
    }

    private String reply(
            TemplateChatRequest request,
            DimensionTemplate previous,
            DimensionTemplate current,
            RawMappedTemplate mapped,
            RetrievedMetadata retrievedMetadata,
            String status) {
        List<String> changes = describeChanges(previous, current);
        String changed = changes.isEmpty() ? "" : "已基于上一轮模板更新：" + String.join("；", changes) + "。";
        if ("READY_TO_CONFIRM".equals(status)) {
            return changes.isEmpty()
                    ? "已保留上一轮完整分析框架，本轮没有识别到需要修改的内容。"
                    : changed + "请确认当前完整模板。";
        }
        java.util.ArrayList<ClarificationPlanner.MissingItem> needs = new java.util.ArrayList<>();
        if (!StringUtils.hasText(current.metricId())) needs.add(new ClarificationPlanner.MissingItem("metric", "分析度量"));
        if (!StringUtils.hasText(current.currentPeriod())) needs.add(new ClarificationPlanner.MissingItem(
                "currentPeriod", "当前分析周期（yyyy-MM）"));
        if (!StringUtils.hasText(current.comparisonPeriod())) needs.add(new ClarificationPlanner.MissingItem(
                "comparisonPeriod", "对比基准或对比周期（yyyy-MM）"));
        if (StringUtils.hasText(current.currentPeriod()) && StringUtils.hasText(current.comparisonPeriod())
                && !YearMonth.parse(current.currentPeriod()).isAfter(YearMonth.parse(current.comparisonPeriod()))) {
            needs.add(new ClarificationPlanner.MissingItem("periodOrder", "当前周期需晚于对比周期"));
        }
        java.util.LinkedHashSet<String> mappingTerms = new java.util.LinkedHashSet<>();
        mapped.mappingIssues().forEach(issue -> mappingTerms.add(issue.userTerm()));
        mappingTerms.addAll(mapped.unmappedTerms());
        mappingTerms.forEach(term -> needs.add(new ClarificationPlanner.MissingItem(
                "mapping:" + term, term + "的字段归属")));
        String clarification = clarificationPlanner.plan(new ClarificationPlanner.ClarificationRequest(
                "ATTRIBUTION",
                request.message(),
                needs,
                knownAttributionFacts(current),
                attributionMetricCandidates(retrievedMetadata)), request.model());
        return changed + clarification;
    }

    private List<String> knownAttributionFacts(DimensionTemplate template) {
        java.util.ArrayList<String> facts = new java.util.ArrayList<>();
        if (StringUtils.hasText(template.metricName())) facts.add("已识别度量：" + template.metricName());
        if (StringUtils.hasText(template.currentPeriod())) facts.add("当前周期：" + template.currentPeriod());
        if (StringUtils.hasText(template.comparisonPeriod())) facts.add("对比周期：" + template.comparisonPeriod());
        if (!template.filters().isEmpty()) facts.add("已保留维度过滤：" + template.filters().size() + " 项");
        return List.copyOf(facts);
    }

    private List<String> attributionMetricCandidates(RetrievedMetadata metadata) {
        List<String> retrieved = metadata == null ? List.of() : metadata.metrics().stream()
                .map(candidate -> candidate.fieldName())
                .filter(StringUtils::hasText)
                .distinct().limit(6).toList();
        if (!retrieved.isEmpty()) return retrieved;
        return AttributionCatalog.metricIds().stream().filter(id -> id.endsWith("_m"))
                .map(AttributionCatalog::metricName).distinct().limit(6).toList();
    }

    private List<String> describeChanges(DimensionTemplate previous, DimensionTemplate current) {
        java.util.ArrayList<String> changes = new java.util.ArrayList<>();
        if (!safeText(previous.metricId()).equals(safeText(current.metricId()))) {
            changes.add("度量改为" + (StringUtils.hasText(current.metricName()) ? current.metricName() : "未设置"));
        }
        if (!safeText(previous.comparisonPeriod()).equals(safeText(current.comparisonPeriod()))
                || !safeText(previous.currentPeriod()).equals(safeText(current.currentPeriod()))) {
            changes.add("周期改为" + displayPeriod(current.comparisonPeriod()) + "对比" + displayPeriod(current.currentPeriod()));
        }
        if (!filterKeys(previous).equals(filterKeys(current))) {
            changes.add(current.filters().isEmpty() ? "清空维度过滤" : "维度过滤改为" + current.filters().stream()
                    .map(filter -> filter.dimensionName() + " " + filter.operator() + " " + String.join("、", filter.values()))
                    .reduce((left, right) -> left + "，" + right).orElse(""));
        }
        if (!layerKeys(previous).equals(layerKeys(current))) {
            changes.add(current.levels().isEmpty() ? "改为自由探索" : "分析层级改为" + current.levels().stream()
                    .map(layer -> "第" + layer.level() + "层[" + layer.dimensions().stream()
                            .map(DimensionSelection::dimensionName).reduce((left, right) -> left + "、" + right).orElse("") + "]")
                    .reduce((left, right) -> left + "，" + right).orElse(""));
        }
        if (!safeText(previous.continuationMode()).equals(safeText(current.continuationMode()))) {
            changes.add("模板后" + ("AUTO".equals(current.continuationMode()) ? "继续自由探索" : "停止下钻"));
        }
        return List.copyOf(changes);
    }

    private List<String> layerKeys(DimensionTemplate template) {
        return template.levels().stream()
                .map(layer -> layer.level() + ":" + layer.dimensions().stream()
                        .map(DimensionSelection::dimensionId).reduce((left, right) -> left + "," + right).orElse(""))
                .toList();
    }

    private List<String> filterKeys(DimensionTemplate template) {
        return template.filters().stream()
                .map(filter -> filter.dimensionId() + ":" + filter.operator() + ":" + String.join(",", filter.values()))
                .toList();
    }

    private String displayPeriod(String value) {
        return StringUtils.hasText(value) ? value : "未设置";
    }

    private static String normalizePeriod(String value) {
        String text = safeText(value);
        if (text.isEmpty()) return "";
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^(\\d{4})\\s*(?:年|[./-])\\s*(\\d{1,2})\\s*月?$")
                .matcher(text);
        if (!matcher.matches()) return text;
        try {
            return YearMonth.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))).toString();
        } catch (RuntimeException exception) {
            return text;
        }
    }

    private void validatePeriod(String value, String name) {
        if (!StringUtils.hasText(value)) return;
        try { YearMonth.parse(value); }
        catch (RuntimeException exception) { throw new IllegalArgumentException(name + "格式必须是yyyy-MM"); }
    }

    private static String validPeriodOrBlank(String value) {
        String normalized = normalizePeriod(value);
        if (!StringUtils.hasText(normalized)) return "";
        try {
            return YearMonth.parse(normalized).toString();
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private RawSemanticIntent parseSemanticIntent(String content) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(stripFence(content));
        validateExactFields(root, Set.of("analysisTerms", "explicitOrder", "requestsAutoExploration",
                "requestsStopAfterTemplate", "metricTerm", "currentPeriod", "comparisonPeriod",
                "filterTerms", "unmappedTerms"), "SemanticIntent");
        RawSemanticIntent result = objectMapper.treeToValue(root, RawSemanticIntent.class);
        int previousLevel = 0;
        for (int index = 0; index < result.analysisTerms().size(); index++) {
            RawAnalysisTerm term = result.analysisTerms().get(index);
            if (term == null || term.level() < 1 || term.level() > previousLevel + 1
                    || term.level() < previousLevel || !StringUtils.hasText(term.term())) {
                throw new IllegalArgumentException("SemanticIntent.analysisTerms层级必须有序连续且包含用户原词");
            }
            previousLevel = term.level();
        }
        return result;
    }

    private RawMappedTemplate parseMappedTemplate(String content) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(stripFence(content));
        validateExactFields(root, Set.of("name", "mode", "metricId", "currentPeriod", "comparisonPeriod", "filters", "levels", "continuationMode", "summary",
                "unmappedTerms", "mappingIssues"), "DimensionTemplate");
        return objectMapper.treeToValue(root, RawMappedTemplate.class);
    }

    private void validateExactFields(JsonNode node, Set<String> expected, String name) {
        if (!(node instanceof ObjectNode object)) {
            throw new IllegalArgumentException(name + "必须是JSON对象");
        }
        LinkedHashSet<String> actual = new LinkedHashSet<>();
        object.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(name + "字段必须且只能为：" + expected);
        }
    }

    private LlmResultMessage complete(List<ChatMessage> messages, String fallback, String model) {
        return StringUtils.hasText(model)
                ? llmClient.completeWithMessage(messages, fallback, model)
                : llmClient.completeWithMessage(messages, fallback);
    }

    private static String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    private String stripFence(String content) {
        String value = content == null ? "" : content.trim();
        if (!value.startsWith("```")) return value;
        int first = value.indexOf('\n');
        int last = value.lastIndexOf("```");
        return first < 0 || last <= first ? value : value.substring(first + 1, last).trim();
    }

    private String conciseMessage(Throwable throwable) {
        String message = throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
        return message.replaceAll("[\\r\\n\\t]+", " ").trim();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawMappedTemplate(
            String name,
            String mode,
            String metricId,
            String currentPeriod,
            String comparisonPeriod,
            List<RawFilter> filters,
            List<RawLayer> levels,
            String continuationMode,
            String summary,
            List<String> unmappedTerms,
            List<MappingIssue> mappingIssues) {

        private RawMappedTemplate {
            filters = filters == null ? List.of() : List.copyOf(filters);
            levels = levels == null ? List.of() : List.copyOf(levels);
            unmappedTerms = unmappedTerms == null ? List.of() : List.copyOf(unmappedTerms);
            mappingIssues = mappingIssues == null ? List.of() : List.copyOf(mappingIssues);
        }

        private RawMappedTemplate withAuthoritativePeriods(RelativeTimeResolver.ResolvedPeriodPair periods) {
            return new RawMappedTemplate(
                    name, mode, metricId,
                    periods.currentPeriod(), periods.comparisonPeriod(),
                    filters, levels, continuationMode, summary, unmappedTerms, mappingIssues);
        }

        private RawMappedTemplate withValidPeriods() {
            return new RawMappedTemplate(
                    name, mode, metricId,
                    validPeriodOrBlank(currentPeriod), validPeriodOrBlank(comparisonPeriod),
                    filters, levels, continuationMode, summary, unmappedTerms, mappingIssues);
        }
    }

    private record RawLayer(int level, List<RawDimension> dimensions) {
        private RawLayer { dimensions = dimensions == null ? List.of() : List.copyOf(dimensions); }
    }

    private record RawDimension(
            String dimensionId, String userTerm, String rationale, String confidence) {
    }

    private record RawFilter(
            String dimensionId, String userTerm, String operator, List<String> values,
            String rationale, String confidence) {
        private RawFilter { values = values == null ? List.of() : List.copyOf(values); }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    private record RawSemanticIntent(
            List<RawAnalysisTerm> analysisTerms,
            boolean explicitOrder,
            boolean requestsAutoExploration,
            boolean requestsStopAfterTemplate,
            String metricTerm,
            String currentPeriod,
            String comparisonPeriod,
            List<RawFilterTerm> filterTerms,
            List<String> unmappedTerms) {

        private RawSemanticIntent {
            analysisTerms = analysisTerms == null ? List.of() : List.copyOf(analysisTerms);
            filterTerms = filterTerms == null ? List.of() : List.copyOf(filterTerms);
            unmappedTerms = unmappedTerms == null ? List.of() : List.copyOf(unmappedTerms);
        }

        private RawSemanticIntent withAuthoritativePeriods(RelativeTimeResolver.ResolvedPeriodPair periods) {
            return new RawSemanticIntent(
                    analysisTerms, explicitOrder, requestsAutoExploration, requestsStopAfterTemplate, metricTerm,
                    periods.currentPeriod(), periods.comparisonPeriod(),
                    filterTerms, unmappedTerms);
        }
    }

    private record RawAnalysisTerm(String term, int level, String context) {
    }

    private record RawFilterTerm(
            String dimensionTerm, String operator, List<String> values, String context) {
        private RawFilterTerm { values = values == null ? List.of() : List.copyOf(values); }
    }
}
