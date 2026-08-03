package com.company.paymentanalysis.chat;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

import com.company.paymentanalysis.chat.ChatQueryInterpreter.ActionOperation;
import com.company.paymentanalysis.chat.ChatQueryInterpreter.ActionPlan;
import com.company.paymentanalysis.chat.ChatQueryInterpreter.FilterAction;
import com.company.paymentanalysis.chat.ChatQueryInterpreter.FilterOperation;
import com.company.paymentanalysis.chat.ChatQueryInterpreter.QueryAction;
import com.company.paymentanalysis.chat.ChatQueryInterpreter.QueryActionResult;
import com.company.paymentanalysis.chat.ChatQueryInterpreter.SortAction;
import com.company.paymentanalysis.controller.ChatQueryController.ChatQueryPlan;
import com.company.paymentanalysis.controller.ChatQueryController.ChatRequest;
import com.company.paymentanalysis.controller.ChatQueryController.ChatResponse;
import com.company.paymentanalysis.controller.ChatQueryController.DimensionFilter;
import com.company.paymentanalysis.controller.ChatQueryController.QueryContext;
import com.company.paymentanalysis.controller.ChatQueryController.QueryFilter;
import com.company.paymentanalysis.controller.ChatQueryController.QueryResult;
import com.company.paymentanalysis.controller.ChatQueryController.ResultColumn;
import com.company.paymentanalysis.controller.ChatQueryController.SortSpec;
import com.company.paymentanalysis.controller.ChatQueryController.WorkflowStep;
import com.company.paymentanalysis.query.QueryMetadataCatalog;
import com.company.paymentanalysis.smartbi.SmartBiClient;
import com.company.paymentanalysis.smartbi.SmartBiModels.Filter;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryRequest;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryResponse;
import com.company.paymentanalysis.smartbi.SmartBiQueryBuilder;
import com.company.paymentanalysis.smartbi.SmartBiSqlPreview;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient.LlmResultMessage;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final String RESPONSE = "response";

    private final ChatQueryInterpreter interpreter;
    private final SmartBiQueryBuilder queryBuilder;
    private final SmartBiClient smartBiClient;
    private final CompiledGraph<ChatState> graph;

    public ChatQueryWorkflowService(
            ChatQueryInterpreter interpreter,
            SmartBiQueryBuilder queryBuilder,
            SmartBiClient smartBiClient) throws GraphStateException {
        this.interpreter = interpreter;
        this.queryBuilder = queryBuilder;
        this.smartBiClient = smartBiClient;
        this.graph = new StateGraph<>(
                        ChatState.SCHEMA,
                        (AgentStateFactory<ChatState>) ChatState::new)
                .addNode("interpretQueryAction", node_async(this::interpretQueryAction))
                .addNode("mergeConversationContext", node_async(this::mergeConversationContext))
                .addNode("validateQueryContext", node_async(this::validateQueryContext))
                .addNode("buildSmartBiQuery", node_async(this::buildSmartBiQuery))
                .addNode("executeSmartBiQuery", node_async(this::executeSmartBiQuery))
                .addNode("generateChatResponse", node_async(this::generateChatResponse))
                .addEdge(START, "interpretQueryAction")
                .addEdge("interpretQueryAction", "mergeConversationContext")
                .addEdge("mergeConversationContext", "validateQueryContext")
                .addEdge("validateQueryContext", "buildSmartBiQuery")
                .addEdge("buildSmartBiQuery", "executeSmartBiQuery")
                .addEdge("executeSmartBiQuery", "generateChatResponse")
                .addEdge("generateChatResponse", END)
                .compile();
    }

    public ChatResponse query(ChatRequest request) {
        return graph.invoke(Map.of(
                        REQUEST, request,
                        CONTEXT, normalize(request.context()),
                        STEPS, List.of()))
                .flatMap(state -> state.<ChatResponse>value(RESPONSE))
                .orElseThrow(() -> new IllegalStateException("LangGraph4j 未生成查数结果"));
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
        String normalizationDetail = result.normalizationNotes().isEmpty()
                ? ""
                : "；" + String.join("；", result.normalizationNotes());
        return Map.of(
                QUERY_ACTION, result.action(),
                QUERY_EXPLANATION, result.explanation(),
                LLM_MESSAGE, result.llmMessage(),
                STEPS, appendStep(state, new WorkflowStep(
                        "interpretQueryAction",
                        "大模型生成 QueryAction JSON",
                        "COMPLETED",
                        interpreter.engineLabel(request.model()) + " 已返回时间、度量和维度 action"
                                + normalizationDetail)));
    }

    private Map<String, Object> mergeConversationContext(ChatState state) {
        QueryContext merged = merge(required(state, QUERY_ACTION));
        return Map.of(
                CONTEXT, merged,
                STEPS, appendStep(state, new WorkflowStep(
                        "mergeConversationContext",
                        "执行 action 并合并会话状态",
                        "COMPLETED",
                        contextSummary(merged))));
    }

    private Map<String, Object> validateQueryContext(ChatState state) {
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

    private Map<String, Object> generateChatResponse(ChatState state) {
        String status = required(state, STATUS);
        QueryContext context = required(state, CONTEXT);
        ChatRequest chatRequest = required(state, REQUEST);
        boolean ready = "ready".equals(status);
        List<String> validationIssues = state.<List<String>>value(VALIDATION_ISSUES).orElseGet(List::of);
        boolean executed = ready && chatRequest.confirmed();
        QueryResult result = executed ? required(state, RESULT) : null;
        String responseStatus = executed ? "completed" : ready ? "confirming" : "clarifying";
        String reply = executed
                ? result.summary()
                : ready
                        ? confirmationSummary(context)
                        : clarificationReply(validationIssues);
        List<WorkflowStep> steps = appendStep(state, new WorkflowStep(
                "generateChatResponse",
                "生成查数回复",
                "COMPLETED",
                executed ? "将 SmartBI 返回转换为表格"
                        : ready ? "返回完整参数并等待显式确认" : "返回补充条件提示"));
        ChatResponse response = new ChatResponse(
                responseStatus,
                reply,
                suggestions(responseStatus, context),
                context,
                result,
                "LangGraph4j → " + interpreter.engineLabel(chatRequest.model()) + " → SmartBI Client",
                steps,
                ready ? required(state, PLAN) : null,
                chatRequest.sessionId(),
                required(state, QUERY_ACTION),
                required(state, QUERY_EXPLANATION),
                required(state, LLM_MESSAGE));
        return Map.of(RESPONSE, response, STEPS, steps);
    }

    private QueryContext merge(QueryAction action) {
        return new QueryContext(
                applyActions(action.metricAction()),
                applyActions(action.dimensionAction()),
                applyFilterActions(action.filterAction()),
                applySortAction(action.sortAction()));
    }

    private List<String> applyActions(ActionPlan plan) {
        ActionOperation operation = plan.operations().get(0);
        return switch (operation.action()) {
            case "CLEAR" -> List.of();
            case "SET" -> List.copyOf(operation.ids());
            default -> throw new IllegalArgumentException(
                    "不支持的列表 action：" + operation.action());
        };
    }

    private List<DimensionFilter> applyFilterActions(FilterAction action) {
        if ("CLEAR".equals(action.operations().get(0).action())) {
            return List.of();
        }
        return action.operations().stream()
                .map(operation -> new DimensionFilter(
                        operation.dimensionId(), operation.operator(), operation.values()))
                .toList();
    }

    private List<SortSpec> applySortAction(SortAction action) {
        return switch (action.action()) {
            case "CLEAR" -> List.of();
            case "SET" -> action.items().stream()
                    .map(item -> new SortSpec(item.fieldId(), item.direction()))
                    .toList();
            default -> throw new IllegalArgumentException("不支持的 sortAction：" + action.action());
        };
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

    private List<WorkflowStep> appendStep(ChatState state, WorkflowStep step) {
        List<WorkflowStep> steps =
                new ArrayList<>(state.<List<WorkflowStep>>value(STEPS).orElseGet(List::of));
        steps.add(step);
        return List.copyOf(steps);
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
                Map.entry(RESPONSE, Channels.base((Supplier<Object>) Map::of)));

        ChatState(Map<String, Object> initData) {
            super(initData);
        }
    }
}
