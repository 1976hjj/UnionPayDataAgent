package com.company.paymentanalysis.chat;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

import com.company.paymentanalysis.analysis.AnalysisContext;
import com.company.paymentanalysis.analysis.IntentRecognitionResult;
import com.company.paymentanalysis.analysis.IntentRecognitionService;
import com.company.paymentanalysis.analysis.IntentRecognitionService.RecognitionResponse;
import com.company.paymentanalysis.analysis.IntentType;
import com.company.paymentanalysis.analysis.QueryPlan;
import com.company.paymentanalysis.analysis.QueryPlanBuilder;
import com.company.paymentanalysis.analysis.QueryPlanValidator;
import com.company.paymentanalysis.chat.ChatQueryInterpreter.Interpretation;
import com.company.paymentanalysis.chat.ChatQueryInterpreter.ActionOperation;
import com.company.paymentanalysis.chat.ChatQueryInterpreter.ActionPlan;
import com.company.paymentanalysis.controller.ChatQueryController.ChatQueryPlan;
import com.company.paymentanalysis.controller.ChatQueryController.ChatRequest;
import com.company.paymentanalysis.controller.ChatQueryController.ChatResponse;
import com.company.paymentanalysis.controller.ChatQueryController.QueryContext;
import com.company.paymentanalysis.controller.ChatQueryController.QueryFilter;
import com.company.paymentanalysis.controller.ChatQueryController.QueryResult;
import com.company.paymentanalysis.controller.ChatQueryController.WorkflowStep;
import com.company.paymentanalysis.execution.AnalysisExecutionResult;
import com.company.paymentanalysis.execution.ExecutionStatus;
import com.company.paymentanalysis.handler.IntentHandlerRouter;
import com.company.paymentanalysis.smartbi.SmartBiModels.Filter;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryRequest;
import com.company.paymentanalysis.smartbi.SmartBiQueryBuilder;
import com.company.paymentanalysis.smartbi.SmartBiSqlPreview;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    private static final String INTERPRETATION = "interpretation";
    private static final String RECOGNITION = "recognition";
    private static final String QUERY_PLAN = "queryPlan";
    private static final String SMARTBI_REQUEST = "smartBiRequest";
    private static final String LLM_MESSAGE = "llmMessage";
    private static final String STATUS = "status";
    private static final String PLAN = "plan";
    private static final String RESULT = "result";
    private static final String ANALYSIS_EXECUTION = "analysisExecution";
    private static final String ANALYSIS_REPLY = "analysisReply";
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

    private final ChatQueryInterpreter interpreter;
    private final IntentRecognitionService recognitionService;
    private final QueryPlanBuilder queryPlanBuilder;
    private final QueryPlanValidator queryPlanValidator;
    private final SmartBiQueryBuilder smartBiQueryBuilder;
    private final IntentHandlerRouter intentHandlerRouter;
    private final ChatAnalysisResultAdapter resultAdapter;
    private final Clock clock;
    private final CompiledGraph<ChatState> graph;

    public ChatQueryWorkflowService(
            ChatQueryInterpreter interpreter,
            IntentRecognitionService recognitionService,
            QueryPlanBuilder queryPlanBuilder,
            QueryPlanValidator queryPlanValidator,
            SmartBiQueryBuilder smartBiQueryBuilder,
            IntentHandlerRouter intentHandlerRouter,
            ChatAnalysisResultAdapter resultAdapter,
            Clock clock) throws GraphStateException {
        this.interpreter = interpreter;
        this.recognitionService = recognitionService;
        this.queryPlanBuilder = queryPlanBuilder;
        this.queryPlanValidator = queryPlanValidator;
        this.smartBiQueryBuilder = smartBiQueryBuilder;
        this.intentHandlerRouter = intentHandlerRouter;
        this.resultAdapter = resultAdapter;
        this.clock = clock;
        this.graph = new StateGraph<>(
                ChatState.SCHEMA,
                (AgentStateFactory<ChatState>) ChatState::new)
                .addNode("interpretMessage", node_async(this::interpretMessage))
                .addNode("mergeConversationContext", node_async(this::mergeConversationContext))
                .addNode("validateQueryScope", node_async(this::validateQueryScope))
                .addNode("buildSmartBiQueryPlan", node_async(this::buildSmartBiQueryPlan))
                .addNode("executeSmartBiQuery", node_async(this::executeSmartBiQuery))
                .addNode("generateChatResponse", node_async(this::generateChatResponse))
                .addEdge(START, "interpretMessage")
                .addEdge("interpretMessage", "mergeConversationContext")
                .addEdge("mergeConversationContext", "validateQueryScope")
                .addEdge("validateQueryScope", "buildSmartBiQueryPlan")
                .addEdge("buildSmartBiQueryPlan", "executeSmartBiQuery")
                .addEdge("executeSmartBiQuery", "generateChatResponse")
                .addEdge("generateChatResponse", END)
                .compile();
    }

    public ChatResponse query(ChatRequest request) {
        QueryContext context = normalize(request.context());
        return graph.invoke(Map.of(
                        REQUEST, request,
                        CONTEXT, context,
                        STEPS, List.of()))
                .flatMap(state -> state.<ChatResponse>value(RESPONSE))
                .orElseThrow(() -> new IllegalStateException("LangGraph4j 未生成查数结果"));
    }

    private Map<String, Object> interpretMessage(ChatState state) {
        ChatRequest request = required(state, REQUEST);
        QueryContext current = required(state, CONTEXT);
        RecognitionResponse recognitionResponse = recognitionService.recognize(
                request.message(), AnalysisContext.from(current, LocalDate.now(clock)));
        IntentRecognitionResult recognition = recognitionResponse.result();
        Interpretation parsed = interpreter.interpretForContextMerge(request.message(), current);
        WorkflowStep step = new WorkflowStep(
                "interpretMessage",
                "大模型解析查询意图",
                "COMPLETED",
                recognitionService.engineLabel() + " 已识别意图：" + recognition.intent()
                        + "；置信度：" + recognition.confidence());
        return Map.of(
                INTERPRETATION, parsed,
                RECOGNITION, recognition,
                LLM_MESSAGE, recognitionResponse.llmMessage(),
                STEPS, appendStep(state, step));
    }

    private Map<String, Object> mergeConversationContext(ChatState state) {
        QueryContext current = required(state, CONTEXT);
        Interpretation parsed = required(state, INTERPRETATION);
        QueryContext merged = merge(current, parsed);
        WorkflowStep step = new WorkflowStep(
                "mergeConversationContext",
                "恢复并合并会话记忆",
                "COMPLETED",
                "按用户与会话恢复上下文；" + contextSummary(merged));
        return Map.of(
                CONTEXT, merged,
                STEPS, appendStep(state, step));
    }

    private Map<String, Object> validateQueryScope(ChatState state) {
        Interpretation parsed = required(state, INTERPRETATION);
        QueryContext context = required(state, CONTEXT);
        IntentRecognitionResult recognition = required(state, RECOGNITION);
        QueryPlan queryPlan = queryPlanValidator.validate(queryPlanBuilder.build(
                recognition, AnalysisContext.from(context, LocalDate.now(clock))));
        String status;
        String detail;
        if (queryPlan.intent() == IntentType.OUT_OF_SCOPE || "OUT_OF_SCOPE".equals(parsed.intent())) {
            status = "rejected";
            detail = "请求超出支付查数范围，后续查询节点将跳过";
        } else if ("GREETING".equals(parsed.intent()) || "RESET".equals(parsed.intent())
                || queryPlan.isClarification()) {
            status = "clarifying";
            detail = queryPlan.clarificationQuestion().isBlank()
                    ? "当前无需取数，等待用户补充查询条件"
                    : queryPlan.clarificationQuestion();
        } else {
            status = "completed";
            detail = "QueryPlan 校验通过：指标、时间、维度和对比对象完整";
        }
        return Map.of(
                STATUS, status,
                QUERY_PLAN, queryPlan,
                STEPS, appendStep(state, new WorkflowStep(
                        "validateQueryScope", "校验查询范围", "COMPLETED", detail)));
    }

    private Map<String, Object> buildSmartBiQueryPlan(ChatState state) {
        if (!"completed".equals(required(state, STATUS))) {
            return Map.of(STEPS, appendStep(state, skipped(
                    "buildSmartBiQueryPlan", "生成 SmartBI 查询计划", "查询条件尚未满足，未生成 JSON")));
        }
        QueryPlan queryPlan = required(state, QUERY_PLAN);
        QueryRequest smartBiRequest = smartBiQueryBuilder.build(queryPlan);
        List<String> rows = displayRows(smartBiRequest);
        List<String> columns = List.of(metricDisplay(queryPlan.metricCode())
                + " (" + smartBiQueryBuilder.metricField(queryPlan.metricCode()) + ")");
        List<QueryFilter> displayFilters = smartBiRequest.filters().stream()
                .map(this::displayFilter)
                .toList();
        ChatQueryPlan plan = new ChatQueryPlan(
                "Mock SmartBI", smartBiRequest.dataSetId(), rows, columns,
                displayFilters, SmartBiSqlPreview.from(smartBiRequest));
        String rowDetail = rows.isEmpty() ? "汇总查询" : "分组：" + String.join("、", rows);
        return Map.of(
                PLAN, plan,
                SMARTBI_REQUEST, smartBiRequest,
                STEPS, appendStep(state, new WorkflowStep(
                        "buildSmartBiQueryPlan",
                        "生成 SmartBI 查询计划",
                        "COMPLETED",
                        rowDetail + "；度量：" + String.join("、", columns)
                                + "；本步骤已形成可替换真实接口的结构化 JSON")));
    }

    private Map<String, Object> executeSmartBiQuery(ChatState state) {
        if (!"completed".equals(required(state, STATUS))) {
            return Map.of(STEPS, appendStep(state, skipped(
                    "executeSmartBiQuery", "执行 SmartBI 查询", "未执行数据查询")));
        }
        QueryContext context = required(state, CONTEXT);
        QueryPlan queryPlan = required(state, QUERY_PLAN);
        AnalysisExecutionResult execution = intentHandlerRouter.route(
                queryPlan,
                AnalysisContext.from(context, LocalDate.now(clock)));
        ChatAnalysisResultAdapter.AdaptedAnalysisResult adapted =
                resultAdapter.adapt(execution, queryPlan);
        String workflowStatus = execution.status() == ExecutionStatus.SUCCESS
                        || execution.status() == ExecutionStatus.NO_DATA
                        || execution.status() == ExecutionStatus.PARTIAL_SUCCESS
                ? "COMPLETED"
                : "FAILED";
        return Map.of(
                RESULT, adapted.result(),
                ANALYSIS_EXECUTION, execution,
                ANALYSIS_REPLY, adapted.reply(),
                STEPS, appendStep(state, new WorkflowStep(
                        "executeSmartBiQuery",
                        "执行 SmartBI 查询并进行 Java 计算",
                        workflowStatus,
                        "状态：" + execution.status()
                                + "；查询次数：" + execution.queryRecords().size()
                                + "；结果行数：" + adapted.result().rows().size())));
    }

    private Map<String, Object> generateChatResponse(ChatState state) {
        String status = required(state, STATUS);
        QueryContext context = required(state, CONTEXT);
        QueryResult result = "completed".equals(status) ? required(state, RESULT) : null;
        ChatQueryPlan plan = "completed".equals(status) ? required(state, PLAN) : null;
        ChatRequest request = required(state, REQUEST);
        QueryPlan validatedQueryPlan = required(state, QUERY_PLAN);
        String reply = "completed".equals(status)
                ? state.<String>value(ANALYSIS_REPLY).orElse("")
                : reply(status, required(state, INTERPRETATION), validatedQueryPlan, context, result);
        List<WorkflowStep> completedSteps = appendStep(state, new WorkflowStep(
                "generateChatResponse",
                "生成前端结果",
                "COMPLETED",
                result == null ? "已生成引导回复" : "已将结构化结果整理为表格和流程说明"));
        ChatResponse response = new ChatResponse(
                status,
                reply,
                suggestions(status, context),
                context,
                result,
                "LangGraph4j → " + recognitionService.engineLabel()
                        + " → SmartBI Client → Java Calculation Engine",
                completedSteps,
                plan,
                request.sessionId(),
                required(state, LLM_MESSAGE));
        return Map.of(
                RESPONSE, response,
                STEPS, completedSteps);
    }

    private QueryContext merge(QueryContext current, Interpretation parsed) {
        if ("RESET".equals(parsed.intent())) {
            return QueryContext.empty();
        }
        String startDate = current.startDate();
        String endDate = current.endDate();
        String periodLabel = current.periodLabel();
        if ("SET".equals(parsed.periodAction())) {
            startDate = parsed.startDate();
            endDate = parsed.endDate();
            periodLabel = parsed.periodLabel();
        } else if ("CLEAR".equals(parsed.periodAction())) {
            startDate = "";
            endDate = "";
            periodLabel = "";
        }

        List<String> metrics = applyActions(current.metricIds(), parsed.metricAction());
        List<String> dimensions = applyActions(current.dimensionIds(), parsed.dimensionAction());
        return new QueryContext(startDate, endDate, periodLabel, metrics, dimensions);
    }

    private List<String> applyActions(List<String> current, ActionPlan plan) {
        if (plan == null || plan.operations() == null || plan.operations().isEmpty()) {
            return List.copyOf(current);
        }
        if (plan.operations().stream().anyMatch(operation -> "KEEP".equals(operation.action()))) {
            return List.copyOf(current);
        }
        if (plan.operations().stream().anyMatch(operation -> "CLEAR".equals(operation.action()))) {
            return List.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>(current);
        for (ActionOperation operation : plan.operations()) {
            if ("REMOVE".equals(operation.action()) && operation.ids() != null) {
                values.removeAll(operation.ids());
            }
        }
        for (ActionOperation operation : plan.operations()) {
            if ("ADD".equals(operation.action()) && operation.ids() != null) {
                values.addAll(operation.ids());
            }
        }
        return List.copyOf(values);
    }

    private String reply(
            String status,
            Interpretation parsed,
            QueryPlan queryPlan,
            QueryContext context,
            QueryResult result) {
        if ("rejected".equals(status)) {
            return "我目前只能处理支付数据查数，不能执行写作、闲聊或其他任务。你可以直接告诉我时间、度量和分组维度。";
        }
        if ("RESET".equals(parsed.intent())) {
            return "已清空当前查询条件。请告诉我下一次想查的时间和度量。";
        }
        if ("GREETING".equals(parsed.intent())) {
            return "你好，我只负责支付数据查数。你可以说“查本月交易金额，按受理渠道分组”。";
        }
        if ("clarifying".equals(status)) {
            return queryPlan.clarificationQuestion().isBlank()
                    ? "查询条件还不完整，请补充指标、时间、分组维度或两个对比对象。"
                    : queryPlan.clarificationQuestion();
        }
        return "已根据多轮对话完成查询：" + result.summary() + "。下方可以查看结果和 LangGraph4j 执行过程。";
    }

    private List<String> suggestions(String status, QueryContext context) {
        if ("completed".equals(status)) {
            return List.of("再加上交易笔数", "改为按地区分组", "看汇总");
        }
        if (context.metricIds().isEmpty()) {
            return List.of("查交易金额", "查交易笔数", "查支付成功率");
        }
        return List.of("查本月交易金额", "看最近7天支付成功率", "查7月各渠道交易金额");
    }

    private String contextSummary(QueryContext context) {
        String period = context.hasPeriod() ? context.periodLabel() : "未指定时间";
        String metrics = context.metricIds().isEmpty()
                ? "未指定度量"
                : names(context.metricIds(), METRIC_NAMES);
        String dimensions = context.dimensionIds().isEmpty()
                ? "不分组"
                : names(context.dimensionIds(), DIMENSION_NAMES);
        return "时间：" + period + "；度量：" + metrics + "；维度：" + dimensions;
    }

    private String names(List<String> ids, Map<String, String> catalog) {
        return ids.stream().map(catalog::get).toList().stream().reduce((a, b) -> a + "、" + b).orElse("");
    }

    private List<String> displayRows(QueryRequest request) {
        Map<String, String> fieldNames = Map.ofEntries(
                Map.entry("sett_dt_Year", "年"),
                Map.entry("sett_dt_Month2", "月"),
                Map.entry("sett_dt_Day", "日"),
                Map.entry("accept_channel", "受理渠道"),
                Map.entry("region_name", "地区"),
                Map.entry("acq_mkt_ch", "收单地区"),
                Map.entry("merchant_type", "商户类型"),
                Map.entry("payment_method", "支付方式"));
        return request.rows().stream()
                .map(field -> fieldNames.getOrDefault(field, field) + " (" + field + ")")
                .toList();
    }

    private String metricDisplay(String metricCode) {
        return switch (metricCode) {
            case "rmbAmount" -> "人民币总金额";
            case "transactionAmount" -> "交易金额";
            case "transactionCount" -> "交易笔数";
            case "successRate" -> "支付成功率";
            default -> metricCode;
        };
    }

    private QueryFilter displayFilter(Filter filter) {
        String name = switch (filter.name()) {
            case "trade_date" -> "交易日期 (trade_date)";
            case "sett_dt_Month2" -> "月 (sett_dt_Month2)";
            case "sett_dt_Year" -> "年 (sett_dt_Year)";
            case "acq_mkt_ch" -> "收单地区 (acq_mkt_ch)";
            case "region_name" -> "地区 (region_name)";
            case "accept_channel" -> "受理渠道 (accept_channel)";
            default -> filter.name();
        };
        return new QueryFilter(name, filter.operation(), filter.values());
    }

    private WorkflowStep skipped(String node, String name, String detail) {
        return new WorkflowStep(node, name, "SKIPPED", detail);
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
                context.dimensionIds() == null ? List.of() : List.copyOf(context.dimensionIds()));
    }

    private List<WorkflowStep> appendStep(ChatState state, WorkflowStep step) {
        List<WorkflowStep> steps = new ArrayList<>(
                state.<List<WorkflowStep>>value(STEPS).orElseGet(List::of));
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
                Map.entry(INTERPRETATION, Channels.base((Supplier<Object>) Map::of)),
                Map.entry(RECOGNITION, Channels.base((Supplier<Object>) Map::of)),
                Map.entry(QUERY_PLAN, Channels.base((Supplier<Object>) Map::of)),
                Map.entry(SMARTBI_REQUEST, Channels.base((Supplier<Object>) Map::of)),
                Map.entry(LLM_MESSAGE, Channels.base((Supplier<Object>) Map::of)),
                Map.entry(STATUS, Channels.base(() -> "")),
                Map.entry(PLAN, Channels.base((Supplier<Object>) Map::of)),
                Map.entry(RESULT, Channels.base((Supplier<Object>) Map::of)),
                Map.entry(ANALYSIS_EXECUTION, Channels.base((Supplier<Object>) Map::of)),
                Map.entry(ANALYSIS_REPLY, Channels.base(() -> "")),
                Map.entry(STEPS, Channels.base((Supplier<List<WorkflowStep>>) List::of)),
                Map.entry(RESPONSE, Channels.base((Supplier<Object>) Map::of)));

        ChatState(Map<String, Object> initData) {
            super(initData);
        }
    }
}
