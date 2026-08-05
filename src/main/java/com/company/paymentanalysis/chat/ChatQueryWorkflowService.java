package com.company.paymentanalysis.chat;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

import com.company.paymentanalysis.chat.ChatQueryInterpreter.QueryAction;
import com.company.paymentanalysis.chat.ChatQueryInterpreter.QueryActionResult;
import com.company.paymentanalysis.chat.ChatQueryInterpreter.QueryInterpretationException;
import com.company.paymentanalysis.controller.ChatQueryController.ChatQueryPlan;
import com.company.paymentanalysis.controller.ChatQueryController.ChatRequest;
import com.company.paymentanalysis.controller.ChatQueryController.ChatResponse;
import com.company.paymentanalysis.controller.ChatQueryController.QueryContext;
import com.company.paymentanalysis.controller.ChatQueryController.QueryFilter;
import com.company.paymentanalysis.controller.ChatQueryController.QueryResult;
import com.company.paymentanalysis.controller.ChatQueryController.ResultColumn;
import com.company.paymentanalysis.controller.ChatQueryController.WorkflowStep;
import com.company.paymentanalysis.query.QueryMetadataCatalog;
import com.company.paymentanalysis.smartbi.SmartBiClient;
import com.company.paymentanalysis.smartbi.SmartBiModels.Filter;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryRequest;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryResponse;
import com.company.paymentanalysis.smartbi.SmartBiQueryBuilder;
import com.company.paymentanalysis.smartbi.SmartBiSqlPreview;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient.LlmResultMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.AgentStateFactory;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;
import org.springframework.stereotype.Service;

@Service
public class ChatQueryWorkflowService {

    private static final String REQUEST = "request";
    private static final String CONTEXT = "context";
    private static final String QUERY_ACTION = "queryAction";
    private static final String QUERY_EXPLANATION = "queryExplanation";
    private static final String SMARTBI_REQUEST = "smartBiRequest";
    private static final String SMARTBI_RESPONSE = "smartBiResponse";
    private static final String LLM_MESSAGE = "llmMessage";
    private static final String STATUS = "status";
    private static final String VALIDATION_ISSUES = "validationIssues";
    private static final String PLAN = "plan";
    private static final String RESULT = "result";
    private static final String STEPS = "steps";
    private static final String FAILURE_NODE = "failureNode";
    private static final String FAILURE_NAME = "failureName";
    private static final String FAILURE_DETAIL = "failureDetail";
    private static final List<NodeDescriptor> WORKFLOW_NODES = List.of(
            new NodeDescriptor("interpretQueryAction", "大模型生成 QueryState JSON"),
            new NodeDescriptor("validateQueryContext", "校验最终查询状态"),
            new NodeDescriptor("buildSmartBiQuery", "生成 SmartBI QueryRequest JSON"),
            new NodeDescriptor("executeSmartBiQuery", "调用 SmartBI 接口"),
            new NodeDescriptor("generateChatResponse", "生成查数回复"));

    private final ChatQueryInterpreter interpreter;
    private final SmartBiQueryBuilder queryBuilder;
    private final SmartBiClient smartBiClient;
    private final ObjectMapper objectMapper;
    private final CompiledGraph<ChatState> graph;

    public ChatQueryWorkflowService(
            ChatQueryInterpreter interpreter,
            SmartBiQueryBuilder queryBuilder,
            SmartBiClient smartBiClient,
            ObjectMapper objectMapper) throws GraphStateException {
        this.interpreter = interpreter;
        this.queryBuilder = queryBuilder;
        this.smartBiClient = smartBiClient;
        this.objectMapper = objectMapper;
        this.graph = new StateGraph<>(
                        ChatState.SCHEMA,
                        (AgentStateFactory<ChatState>) ChatState::new)
                .addNode("interpretQueryAction", node_async(state -> runNode(
                        "interpretQueryAction", "大模型生成 QueryState JSON", state,
                        this::interpretQueryAction)))
                .addNode("validateQueryContext", node_async(state -> runNode(
                        "validateQueryContext", "校验最终查询状态", state,
                        this::validateQueryContext)))
                .addNode("buildSmartBiQuery", node_async(state -> runNode(
                        "buildSmartBiQuery", "生成 SmartBI QueryRequest JSON", state,
                        this::buildSmartBiQuery)))
                .addNode("executeSmartBiQuery", node_async(state -> runNode(
                        "executeSmartBiQuery", "调用 SmartBI 接口", state,
                        this::executeSmartBiQuery)))
                .addEdge(START, "interpretQueryAction")
                .addEdge("interpretQueryAction", "validateQueryContext")
                .addEdge("validateQueryContext", "buildSmartBiQuery")
                .addEdge("buildSmartBiQuery", "executeSmartBiQuery")
                .addEdge("executeSmartBiQuery", END)
                .compile();
    }

    public ChatResponse query(ChatRequest request) {
        try {
            ChatState finalState = graph.invoke(Map.of(
                            REQUEST, request,
                            CONTEXT, normalize(request.context()),
                            STEPS, List.of()))
                    .orElseThrow(() -> new IllegalStateException("LangGraph4j 未生成查数结果"));
            return generateChatResponse(finalState);
        } catch (RuntimeException exception) {
            return failedResponse(request, exception);
        }
    }

    private Map<String, Object> runNode(
            String node, String name, ChatState state,
            Function<ChatState, Map<String, Object>> operation) {
        try {
            return operation.apply(state);
        } catch (RuntimeException exception) {
            QueryInterpretationException interpretationFailure =
                    findCause(exception, QueryInterpretationException.class);
            String detail = conciseMessage(
                    interpretationFailure == null ? rootCause(exception) : interpretationFailure);
            Map<String, Object> failure = new LinkedHashMap<>();
            failure.put(STATUS, "failed");
            failure.put(FAILURE_NODE, node);
            failure.put(FAILURE_NAME, name);
            failure.put(FAILURE_DETAIL, detail);
            failure.put(STEPS, appendStep(state, new WorkflowStep(node, name, "FAILED", detail)));
            if (interpretationFailure != null && interpretationFailure.llmMessage() != null) {
                failure.put(LLM_MESSAGE, interpretationFailure.llmMessage());
            }
            return Map.copyOf(failure);
        }
    }

    private ChatResponse failedResponse(ChatRequest request, RuntimeException exception) {
        QueryInterpretationException interpretationFailure =
                findCause(exception, QueryInterpretationException.class);
        Throwable root = rootCause(exception);
        String failedNode = "workflow";
        String failedName = "LangGraph4j 查数流程";
        String error = conciseMessage(interpretationFailure == null ? root : interpretationFailure);
        LlmResultMessage llmMessage = interpretationFailure == null
                ? null
                : interpretationFailure.llmMessage();
        List<WorkflowStep> steps = failureSteps(failedNode, error);
        String reply = "查询流程在“" + failedName + "”节点停止：" + error
                + "。后续 SmartBI 查询未执行。";
        return new ChatResponse(
                "rejected",
                reply,
                List.of(),
                normalize(request.context()),
                null,
                "LangGraph4j → " + interpreter.engineLabel(request.model()) + " → SmartBI Client",
                steps,
                null,
                request.sessionId(),
                null,
                "模型输出或流程数据未通过校验，当前查询状态未被更新。",
                llmMessage);
    }

    private List<WorkflowStep> failureSteps(String failedNode, String error) {
        boolean knownNode = WORKFLOW_NODES.stream().anyMatch(node -> node.node().equals(failedNode));
        if (!knownNode) {
            List<WorkflowStep> unknownSteps = new ArrayList<>();
            unknownSteps.add(new WorkflowStep(failedNode, "LangGraph4j 查数流程", "FAILED", error));
            for (NodeDescriptor node : WORKFLOW_NODES) {
                unknownSteps.add(skipped(node.node(), node.name(), "流程异常，本节点状态不可用"));
            }
            return List.copyOf(unknownSteps);
        }
        List<WorkflowStep> steps = new ArrayList<>();
        boolean failed = false;
        for (NodeDescriptor node : WORKFLOW_NODES) {
            if (node.node().equals(failedNode)) {
                steps.add(new WorkflowStep(node.node(), node.name(), "FAILED", error));
                failed = true;
            } else if (failed) {
                steps.add(skipped(node.node(), node.name(), "上游节点失败，本节点未执行"));
            } else {
                steps.add(new WorkflowStep(
                        node.node(), node.name(), "COMPLETED", "该节点在流程中断前已完成"));
            }
        }
        return List.copyOf(steps);
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private <T extends Throwable> T findCause(Throwable throwable, Class<T> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    private String conciseMessage(Throwable throwable) {
        String message = throwable == null ? "未知错误" : throwable.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable == null ? "未知错误" : throwable.getClass().getSimpleName();
        }
        String normalized = message.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s{2,}", " ").trim();
        return normalized.length() <= 400 ? normalized : normalized.substring(0, 400) + "…";
    }

    private Map<String, Object> interpretQueryAction(ChatState state) {
        ChatRequest request = required(state, REQUEST);
        if (request.confirmed()) {
            return Map.of(
                    QUERY_ACTION, QueryAction.fromContext(required(state, CONTEXT)),
                    QUERY_EXPLANATION, "用户已确认页面中的查询条件，本轮直接复用，不再次调用大模型。",
                    LLM_MESSAGE, new LlmResultMessage(
                            interpreter.engineLabel(request.model()), "system",
                    "显式确认请求：复用页面已展示的 QueryContext，不再次调用 LLM。",
                            List.of()),
                    STEPS, appendStep(state, new WorkflowStep(
                            "interpretQueryAction",
                            "复用已确认的查询状态",
                            "COMPLETED",
                            "显式 confirmed=true，未再次调用大模型")));
        }
        QueryActionResult result = interpreter.interpret(request, required(state, CONTEXT));
        return Map.of(
                QUERY_ACTION, result.action(),
                CONTEXT, result.action().toContext(),
                QUERY_EXPLANATION, result.explanation(),
                LLM_MESSAGE, result.llmMessage(),
                STEPS, appendStep(state, new WorkflowStep(
                        "interpretQueryAction",
                        "大模型生成 QueryState JSON",
                        "COMPLETED",
                        interpreter.engineLabel(request.model()) + " 已返回完整查询状态")));
    }

    private Map<String, Object> validateQueryContext(ChatState state) {
        if (isFailed(state)) {
            return Map.of(STEPS, appendStep(state, skipped(
                    "validateQueryContext", "校验最终查询状态", "上游节点失败，本节点未执行")));
        }
        QueryContext context = required(state, CONTEXT);
        List<String> missing = new ArrayList<>();
        if (context.metricIds().isEmpty()) {
            missing.add("度量");
        }
        Set<String> selectedFields = new LinkedHashSet<>(context.metricIds());
        selectedFields.addAll(context.dimensionIds());
        if (context.sorts().stream().anyMatch(sort -> !selectedFields.contains(sort.fieldId()))) {
            missing.add("排序字段对应的度量或分组维度");
        }
        String status = missing.isEmpty() ? "ready" : "clarifying";
        String detail = missing.isEmpty()
                ? "查询状态完整，可以生成 SmartBI JSON"
                : "仍需补充：" + String.join("、", missing);
        return Map.of(
                STATUS, status,
                VALIDATION_ISSUES, List.copyOf(missing),
                STEPS, appendStep(state, new WorkflowStep(
                        "validateQueryContext", "校验最终查询状态", "COMPLETED", detail)));
    }

    private Map<String, Object> buildSmartBiQuery(ChatState state) {
        if (isFailed(state)) {
            return Map.of(STEPS, appendStep(state, skipped(
                    "buildSmartBiQuery", "生成 SmartBI QueryRequest JSON", "上游节点失败，本节点未执行")));
        }
        if (!"ready".equals(required(state, STATUS))) {
            return Map.of(STEPS, appendStep(state, skipped(
                    "buildSmartBiQuery", "生成 SmartBI QueryRequest JSON", "查询状态不完整，未生成")));
        }
        QueryContext context = required(state, CONTEXT);
        QueryRequest request = queryBuilder.build(context);
        ChatQueryPlan plan = new ChatQueryPlan(
                "Mock SmartBI",
                request.dataSetId(),
                displayRows(request),
                displayColumns(context),
                request.filters().stream().map(this::displayFilter).toList(),
                SmartBiSqlPreview.from(request),
                request);
        return Map.of(
                SMARTBI_REQUEST, request,
                PLAN, plan,
                STEPS, appendStep(state, new WorkflowStep(
                        "buildSmartBiQuery",
                        "生成 SmartBI QueryRequest JSON",
                        "COMPLETED",
                        "rows=" + request.rows().size() + "，columns=" + request.columns().size()
                                + "，filters=" + request.filters().size())));
    }

    private Map<String, Object> executeSmartBiQuery(ChatState state) {
        if (isFailed(state)) {
            return Map.of(STEPS, appendStep(state, skipped(
                    "executeSmartBiQuery", "调用 SmartBI 接口", "上游节点失败，本节点未执行")));
        }
        if (!"ready".equals(required(state, STATUS))) {
            return Map.of(STEPS, appendStep(state, skipped(
                    "executeSmartBiQuery", "调用 SmartBI 接口", "查询状态不完整，未调用")));
        }
        ChatRequest chatRequest = required(state, REQUEST);
        if (!chatRequest.confirmed()) {
            return Map.of(STEPS, appendStep(state, skipped(
                    "executeSmartBiQuery", "调用 SmartBI 接口", "等待用户确认，尚未调用 SmartBI")));
        }
        QueryRequest request = required(state, SMARTBI_REQUEST);
        QueryResponse response = smartBiClient.query(request);
        QueryResult result = toQueryResult(required(state, CONTEXT), request, response);
        return Map.of(
                SMARTBI_RESPONSE, response,
                RESULT, result,
                STEPS, appendStep(state, new WorkflowStep(
                        "executeSmartBiQuery",
                        "调用 SmartBI 接口",
                        "COMPLETED",
                        "一次查询返回 " + result.rows().size() + " 行数据")));
    }

    private ChatResponse generateChatResponse(ChatState state) {
        String status = required(state, STATUS);
        QueryContext context = converted(state, CONTEXT, QueryContext.class);
        ChatRequest chatRequest = converted(state, REQUEST, ChatRequest.class);
        if ("failed".equals(status)) {
            String failureName = state.<String>value(FAILURE_NAME).orElse("LangGraph4j 查数流程");
            String failureDetail = state.<String>value(FAILURE_DETAIL).orElse("未知错误");
            List<WorkflowStep> steps = appendFinalStep(state, new WorkflowStep(
                    "generateChatResponse",
                    "生成查数回复",
                    "COMPLETED",
                    "已返回节点级错误摘要；SmartBI 未执行"));
            return new ChatResponse(
                    "rejected",
                    "查询流程在“" + failureName + "”节点停止：" + failureDetail
                            + "。后续 SmartBI 查询未执行。",
                    List.of(),
                    context,
                    null,
                    "LangGraph4j → " + interpreter.engineLabel(chatRequest.model()) + " → SmartBI Client",
                    steps,
                    null,
                    chatRequest.sessionId(),
                    null,
                    "模型输出或流程数据未通过校验，当前查询状态未被更新。",
                    convertedOrNull(state, LLM_MESSAGE, LlmResultMessage.class));
        }
        boolean ready = "ready".equals(status);
        List<String> validationIssues = state.<List<String>>value(VALIDATION_ISSUES).orElseGet(List::of);
        boolean executed = ready && chatRequest.confirmed();
        QueryResult result = executed ? converted(state, RESULT, QueryResult.class) : null;
        String responseStatus = executed ? "completed" : ready ? "confirming" : "clarifying";
        String reply = executed
                ? result.summary()
                : ready
                        ? confirmationSummary(context)
                        : clarificationReply(validationIssues);
        List<WorkflowStep> steps = appendFinalStep(state, new WorkflowStep(
                "generateChatResponse",
                "生成查数回复",
                "COMPLETED",
                executed ? "将 SmartBI 返回转换为表格"
                        : ready ? "返回完整参数并等待显式确认" : "返回补充条件提示"));
        return new ChatResponse(
                responseStatus,
                reply,
                suggestions(responseStatus, context),
                context,
                result,
                "LangGraph4j → " + interpreter.engineLabel(chatRequest.model()) + " → SmartBI Client",
                steps,
                ready ? converted(state, PLAN, ChatQueryPlan.class) : null,
                chatRequest.sessionId(),
                converted(state, QUERY_ACTION, QueryAction.class),
                required(state, QUERY_EXPLANATION),
                converted(state, LLM_MESSAGE, LlmResultMessage.class));
    }

    private QueryResult toQueryResult(
            QueryContext context, QueryRequest request, QueryResponse response) {
        List<ResultColumn> columns = new ArrayList<>();
        if (context.dimensionIds().isEmpty()) {
            columns.add(new ResultColumn("scope", "汇总", false));
        }
        context.dimensionIds().forEach(
                id -> columns.add(new ResultColumn(
                        id, QueryMetadataCatalog.displayName(id), false)));
        context.metricIds().forEach(
                id -> columns.add(new ResultColumn(
                        id, QueryMetadataCatalog.displayName(id), true)));

        List<Map<String, String>> rows = response.data().stream().map(source -> {
            Map<String, String> row = new LinkedHashMap<>();
            if (context.dimensionIds().isEmpty()) {
                row.put("scope", "全部");
            }
            for (int index = 0; index < context.dimensionIds().size(); index++) {
                row.put(
                        context.dimensionIds().get(index),
                        text(source.get(request.rows().get(index))));
            }
            for (int index = 0; index < context.metricIds().size(); index++) {
                row.put(
                        context.metricIds().get(index),
                        text(source.get(request.columns().get(index))));
            }
            return Map.copyOf(row);
        }).toList();
        return new QueryResult("查询完成，共返回 " + rows.size() + " 条数据。", columns, rows);
    }

    private String text(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        return value == null ? "" : value.toString();
    }

    private List<String> displayRows(QueryRequest request) {
        return request.rows().stream()
                .map(field -> QueryMetadataCatalog.displayNameBySmartBiField(field)
                        + " (" + field + ")")
                .toList();
    }

    private List<String> displayColumns(QueryContext context) {
        return context.metricIds().stream()
                .map(id -> QueryMetadataCatalog.displayName(id)
                        + " (" + queryBuilder.metricField(id) + ")")
                .toList();
    }

    private QueryFilter displayFilter(Filter filter) {
        String name = QueryMetadataCatalog.displayNameBySmartBiField(filter.name())
                + " (" + filter.name() + ")";
        return new QueryFilter(name, filter.operation(), filter.values());
    }

    private String contextSummary(QueryContext context) {
        String metrics = context.metricIds().isEmpty()
                ? "未指定度量"
                : context.metricIds().stream()
                        .map(QueryMetadataCatalog::displayName)
                        .reduce((a, b) -> a + "、" + b).orElse("");
        String dimensions = context.dimensionIds().isEmpty()
                ? "不分组"
                : context.dimensionIds().stream()
                        .map(QueryMetadataCatalog::displayName)
                        .reduce((a, b) -> a + "、" + b).orElse("");
        return "度量：" + metrics + "；维度：" + dimensions
                + "；维度过滤：" + context.dimensionFilters().size()
                + "；排序：" + context.sorts().size();
    }

    private String confirmationSummary(QueryContext context) {
        String metrics = context.metricIds().stream()
                .map(QueryMetadataCatalog::displayName)
                .reduce((left, right) -> left + "、" + right).orElse("无");
        String dimensions = context.dimensionIds().isEmpty()
                ? "不分组"
                : context.dimensionIds().stream()
                        .map(QueryMetadataCatalog::displayName)
                        .reduce((left, right) -> left + "、" + right).orElse("不分组");
        String filters = context.dimensionFilters().isEmpty()
                ? "无"
                : context.dimensionFilters().stream().map(filter ->
                        QueryMetadataCatalog.displayName(filter.dimensionId()) + " "
                                + filter.operator() + " " + String.join("、", filter.values()))
                        .reduce((left, right) -> left + "；" + right).orElse("无");
        String sorts = context.sorts().isEmpty()
                ? "无"
                : context.sorts().stream().map(sort ->
                        fieldDisplayName(sort.fieldId()) + " " + sort.direction())
                        .reduce((left, right) -> left + "；" + right).orElse("无");
        return "请确认本次查询参数：度量=" + metrics
                + "；分组维度=" + dimensions
                + "；维度过滤=" + filters
                + "；排序=" + sorts
                + "。确认后才会调用 SmartBI。";
    }

    private String clarificationReply(List<String> validationIssues) {
        if (validationIssues.isEmpty()) {
            return "查询条件尚未完整，请补充必要条件。";
        }
        return "查询条件尚未完整：" + String.join("、", validationIssues) + "。";
    }

    private String fieldDisplayName(String fieldId) {
        return QueryMetadataCatalog.displayName(fieldId);
    }

    private List<String> suggestions(String status, QueryContext context) {
        if ("confirming".equals(status)) {
            return List.of();
        }
        if ("completed".equals(status)) {
            return List.of("再加上交易笔数", "增加地区维度", "清空维度看汇总");
        }
        return context.metricIds().isEmpty()
                ? List.of("查交易金额", "查交易笔数", "查支付成功率")
                : List.of("查本月", "查最近7天", "查近半年");
    }

    private QueryContext normalize(QueryContext context) {
        if (context == null) {
            return QueryContext.empty();
        }
        return new QueryContext(
                context.metricIds() == null ? List.of() : List.copyOf(context.metricIds()),
                context.dimensionIds() == null ? List.of() : List.copyOf(context.dimensionIds()),
                context.dimensionFilters() == null ? List.of() : List.copyOf(context.dimensionFilters()),
                context.sorts() == null ? List.of() : List.copyOf(context.sorts()));
    }

    private WorkflowStep skipped(String node, String name, String detail) {
        return new WorkflowStep(node, name, "SKIPPED", detail);
    }

    private boolean isFailed(ChatState state) {
        return "failed".equals(state.<String>value(STATUS).orElse(""));
    }

    private record NodeDescriptor(String node, String name) {
    }

    private List<WorkflowStep> appendStep(ChatState state, WorkflowStep step) {
        List<WorkflowStep> steps =
                new ArrayList<>(state.<List<WorkflowStep>>value(STEPS).orElseGet(List::of));
        steps.add(step);
        return List.copyOf(steps);
    }

    private List<WorkflowStep> appendFinalStep(ChatState state, WorkflowStep step) {
        Object value = state.value(STEPS).orElse(List.of());
        List<WorkflowStep> steps = objectMapper.convertValue(
                value,
                objectMapper.getTypeFactory().constructCollectionType(List.class, WorkflowStep.class));
        List<WorkflowStep> result = new ArrayList<>(steps);
        result.add(step);
        return List.copyOf(result);
    }

    private <T> T converted(ChatState state, String key, Class<T> type) {
        Object value = state.value(key)
                .orElseThrow(() -> new IllegalStateException("LangGraph4j 查数状态缺少 " + key));
        return type.isInstance(value) ? type.cast(value) : objectMapper.convertValue(value, type);
    }

    private <T> T convertedOrNull(ChatState state, String key, Class<T> type) {
        return state.value(key)
                .map(value -> type.isInstance(value) ? type.cast(value) : objectMapper.convertValue(value, type))
                .orElse(null);
    }

    private <T> T required(ChatState state, String key) {
        return state.<T>value(key)
                .orElseThrow(() -> new IllegalStateException("LangGraph4j 查数状态缺少 " + key));
    }

    static final class ChatState extends AgentState {
        static final Map<String, Channel<?>> SCHEMA = Map.ofEntries(
                Map.entry(REQUEST, Channels.base((Supplier<Object>) Map::of)),
                Map.entry(CONTEXT, Channels.base((Supplier<Object>) Map::of)),
                Map.entry(QUERY_ACTION, Channels.base((Supplier<Object>) Map::of)),
                Map.entry(QUERY_EXPLANATION, Channels.base(() -> "")),
                Map.entry(SMARTBI_REQUEST, Channels.base((Supplier<Object>) Map::of)),
                Map.entry(SMARTBI_RESPONSE, Channels.base((Supplier<Object>) Map::of)),
                Map.entry(LLM_MESSAGE, Channels.base((Supplier<Object>) Map::of)),
                Map.entry(STATUS, Channels.base(() -> "")),
                Map.entry(VALIDATION_ISSUES, Channels.base((Supplier<List<String>>) List::of)),
                Map.entry(PLAN, Channels.base((Supplier<Object>) Map::of)),
                Map.entry(RESULT, Channels.base((Supplier<Object>) Map::of)),
                Map.entry(STEPS, Channels.base((Supplier<List<WorkflowStep>>) List::of)),
                Map.entry(FAILURE_NODE, Channels.base(() -> "")),
                Map.entry(FAILURE_NAME, Channels.base(() -> "")),
                Map.entry(FAILURE_DETAIL, Channels.base(() -> "")));

        ChatState(Map<String, Object> initData) {
            super(initData);
        }
    }
}
