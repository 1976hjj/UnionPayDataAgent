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
    private static final String SMARTBI_REQUEST = "smartBiRequest";
    private static final String SMARTBI_RESPONSE = "smartBiResponse";
    private static final String LLM_MESSAGE = "llmMessage";
    private static final String STATUS = "status";
    private static final String PLAN = "plan";
    private static final String RESULT = "result";
    private static final String STEPS = "steps";
    private static final String RESPONSE = "response";

    private static final Map<String, String> METRIC_NAMES = Map.of(
            "transactionAmount", "交易金额",
            "transactionCount", "交易笔数",
            "successRate", "支付成功率");
    private static final Map<String, String> DIMENSION_NAMES = Map.of(
            "tradeYear", "年",
            "tradeMonth", "月",
            "tradeDate", "日",
            "channel", "受理渠道",
            "region", "地区",
            "merchantType", "商户类型",
            "paymentMethod", "支付方式");
    private static final Map<String, String> FIELD_NAMES = Map.ofEntries(
            Map.entry("sett_dt_Year", "年"),
            Map.entry("sett_dt_Month2", "月"),
            Map.entry("sett_dt_Day", "日"),
            Map.entry("accept_channel", "受理渠道"),
            Map.entry("region_name", "地区"),
            Map.entry("merchant_type", "商户类型"),
            Map.entry("payment_method", "支付方式"));

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
                    QUERY_ACTION, QueryAction.keep(),
                    LLM_MESSAGE, new LlmResultMessage(
                            interpreter.engineLabel(), "system",
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
                LLM_MESSAGE, result.llmMessage(),
                STEPS, appendStep(state, new WorkflowStep(
                        "interpretQueryAction",
                        "大模型生成 QueryAction JSON",
                        "COMPLETED",
                        interpreter.engineLabel() + " 已返回时间、度量和维度 action")));
    }

    private Map<String, Object> mergeConversationContext(ChatState state) {
        QueryContext merged = merge(required(state, CONTEXT), required(state, QUERY_ACTION));
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
        if (!context.hasPeriod()) {
            missing.add("时间范围");
        }
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
        boolean executed = ready && chatRequest.confirmed();
        QueryResult result = executed ? required(state, RESULT) : null;
        String responseStatus = executed ? "completed" : ready ? "confirming" : "clarifying";
        String reply = executed
                ? result.summary()
                : ready
                        ? confirmationSummary(context)
                        : context.metricIds().isEmpty()
                                ? "请指定要查询的度量。"
                                : "请指定查询时间范围。";
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
                "LangGraph4j → " + interpreter.engineLabel() + " → SmartBI Client",
                steps,
                ready ? required(state, PLAN) : null,
                chatRequest.sessionId(),
                required(state, QUERY_ACTION),
                required(state, LLM_MESSAGE));
        return Map.of(RESPONSE, response, STEPS, steps);
    }

    private QueryContext merge(QueryContext current, QueryAction action) {
        String startDate = current.startDate();
        String endDate = current.endDate();
        String periodLabel = current.periodLabel();
        if ("SET".equals(action.periodAction())) {
            startDate = action.startDate();
            endDate = action.endDate();
            periodLabel = action.periodLabel();
        } else if ("CLEAR".equals(action.periodAction())) {
            startDate = "";
            endDate = "";
            periodLabel = "";
        }
        return new QueryContext(
                startDate,
                endDate,
                periodLabel,
                applyActions(current.metricIds(), action.metricAction()),
                applyActions(current.dimensionIds(), action.dimensionAction()),
                applyFilterActions(current.dimensionFilters(), action.filterAction()),
                applySortAction(current.sorts(), action.sortAction()));
    }

    private List<String> applyActions(List<String> current, ActionPlan plan) {
        if (plan.operations().stream().anyMatch(operation -> "KEEP".equals(operation.action()))) {
            return List.copyOf(current);
        }
        if (plan.operations().stream().anyMatch(operation -> "CLEAR".equals(operation.action()))) {
            return List.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>(current);
        plan.operations().stream()
                .filter(operation -> "REMOVE".equals(operation.action()))
                .map(ActionOperation::ids)
                .forEach(values::removeAll);
        plan.operations().stream()
                .filter(operation -> "ADD".equals(operation.action()))
                .map(ActionOperation::ids)
                .forEach(values::addAll);
        return List.copyOf(values);
    }

    private List<DimensionFilter> applyFilterActions(
            List<DimensionFilter> current, FilterAction action) {
        if (action.operations().stream().anyMatch(operation -> "KEEP".equals(operation.action()))) {
            return List.copyOf(current);
        }
        if (action.operations().stream().anyMatch(operation -> "CLEAR".equals(operation.action()))) {
            return List.of();
        }
        Map<String, DimensionFilter> filters = new LinkedHashMap<>();
        current.forEach(filter -> filters.put(filter.dimensionId(), filter));
        action.operations().stream()
                .filter(operation -> "REMOVE".equals(operation.action()))
                .map(FilterOperation::dimensionId)
                .forEach(filters::remove);
        action.operations().stream()
                .filter(operation -> "SET".equals(operation.action()))
                .map(operation -> new DimensionFilter(
                        operation.dimensionId(), operation.operator(), operation.values()))
                .forEach(filter -> filters.put(filter.dimensionId(), filter));
        return List.copyOf(filters.values());
    }

    private List<SortSpec> applySortAction(List<SortSpec> current, SortAction action) {
        return switch (action.action()) {
            case "KEEP" -> List.copyOf(current);
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
            columns.add(new ResultColumn("period", "时间范围", false));
        }
        context.dimensionIds().forEach(
                id -> columns.add(new ResultColumn(id, DIMENSION_NAMES.get(id), false)));
        context.metricIds().forEach(
                id -> columns.add(new ResultColumn(id, METRIC_NAMES.get(id), true)));

        List<Map<String, String>> rows = response.data().stream().map(source -> {
            Map<String, String> row = new LinkedHashMap<>();
            if (context.dimensionIds().isEmpty()) {
                row.put("period", context.startDate() + " ～ " + context.endDate());
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
                .map(field -> FIELD_NAMES.getOrDefault(field, field) + " (" + field + ")")
                .toList();
    }

    private List<String> displayColumns(QueryContext context) {
        return context.metricIds().stream()
                .map(id -> METRIC_NAMES.get(id) + " (" + queryBuilder.metricField(id) + ")")
                .toList();
    }

    private QueryFilter displayFilter(Filter filter) {
        String name = "trade_date".equals(filter.name())
                ? "交易日期 (trade_date)"
                : filter.name();
        return new QueryFilter(name, filter.operation(), filter.values());
    }

    private String contextSummary(QueryContext context) {
        String period = context.hasPeriod() ? context.periodLabel() : "未指定时间";
        String metrics = context.metricIds().isEmpty()
                ? "未指定度量"
                : context.metricIds().stream().map(METRIC_NAMES::get).reduce((a, b) -> a + "、" + b).orElse("");
        String dimensions = context.dimensionIds().isEmpty()
                ? "不分组"
                : context.dimensionIds().stream().map(DIMENSION_NAMES::get).reduce((a, b) -> a + "、" + b).orElse("");
        return "时间：" + period + "；度量：" + metrics + "；维度：" + dimensions
                + "；过滤：" + context.dimensionFilters().size()
                + "；排序：" + context.sorts().size();
    }

    private String confirmationSummary(QueryContext context) {
        String metrics = context.metricIds().stream()
                .map(METRIC_NAMES::get).reduce((left, right) -> left + "、" + right).orElse("无");
        String dimensions = context.dimensionIds().isEmpty()
                ? "不分组"
                : context.dimensionIds().stream()
                        .map(DIMENSION_NAMES::get).reduce((left, right) -> left + "、" + right).orElse("不分组");
        String filters = context.dimensionFilters().isEmpty()
                ? "无"
                : context.dimensionFilters().stream().map(filter ->
                        DIMENSION_NAMES.get(filter.dimensionId()) + " "
                                + filter.operator() + " " + String.join("、", filter.values()))
                        .reduce((left, right) -> left + "；" + right).orElse("无");
        String sorts = context.sorts().isEmpty()
                ? "无"
                : context.sorts().stream().map(sort ->
                        fieldDisplayName(sort.fieldId()) + " " + sort.direction())
                        .reduce((left, right) -> left + "；" + right).orElse("无");
        return "请确认本次查询参数：时间=" + context.periodLabel()
                + "（" + context.startDate() + " 至 " + context.endDate() + "）"
                + "；度量=" + metrics
                + "；分组维度=" + dimensions
                + "；维度过滤=" + filters
                + "；排序=" + sorts
                + "。确认后才会调用 SmartBI。";
    }

    private String fieldDisplayName(String fieldId) {
        return METRIC_NAMES.containsKey(fieldId)
                ? METRIC_NAMES.get(fieldId)
                : DIMENSION_NAMES.getOrDefault(fieldId, fieldId);
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
                context.startDate() == null ? "" : context.startDate(),
                context.endDate() == null ? "" : context.endDate(),
                context.periodLabel() == null ? "" : context.periodLabel(),
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
                Map.entry(SMARTBI_REQUEST, Channels.base((Supplier<Object>) Map::of)),
                Map.entry(SMARTBI_RESPONSE, Channels.base((Supplier<Object>) Map::of)),
                Map.entry(LLM_MESSAGE, Channels.base((Supplier<Object>) Map::of)),
                Map.entry(STATUS, Channels.base(() -> "")),
                Map.entry(PLAN, Channels.base((Supplier<Object>) Map::of)),
                Map.entry(RESULT, Channels.base((Supplier<Object>) Map::of)),
                Map.entry(STEPS, Channels.base((Supplier<List<WorkflowStep>>) List::of)),
                Map.entry(RESPONSE, Channels.base((Supplier<Object>) Map::of)));

        ChatState(Map<String, Object> initData) {
            super(initData);
        }
    }
}
