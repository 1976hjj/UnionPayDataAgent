package com.company.paymentanalysis.chat;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

import com.company.paymentanalysis.chat.ChatQueryInterpreter.Interpretation;
import com.company.paymentanalysis.chat.ChatQueryInterpreter.InterpretationResult;
import com.company.paymentanalysis.controller.ChatQueryController.ChatQueryPlan;
import com.company.paymentanalysis.controller.ChatQueryController.ChatRequest;
import com.company.paymentanalysis.controller.ChatQueryController.ChatResponse;
import com.company.paymentanalysis.controller.ChatQueryController.QueryContext;
import com.company.paymentanalysis.controller.ChatQueryController.QueryFilter;
import com.company.paymentanalysis.controller.ChatQueryController.QueryResult;
import com.company.paymentanalysis.controller.ChatQueryController.ResultColumn;
import com.company.paymentanalysis.controller.ChatQueryController.WorkflowStep;
import com.company.paymentanalysis.smartbi.SmartBiSqlPreview;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    private static final Map<String, String> METRIC_FIELDS = Map.of(
            "transactionAmount", "trans_amt",
            "transactionCount", "trans_cnt",
            "successRate", "success_rate");
    private static final Map<String, String> DIMENSION_NAMES = Map.of(
            "channel", "受理渠道",
            "region", "地区",
            "merchantType", "商户类型",
            "paymentMethod", "支付方式");
    private static final Map<String, String> DIMENSION_FIELDS = Map.of(
            "channel", "accept_channel",
            "region", "region_name",
            "merchantType", "merchant_type",
            "paymentMethod", "payment_method");
    private static final Map<String, List<String>> DIMENSION_MEMBERS = Map.of(
            "channel", List.of("线上渠道", "线下渠道", "移动端", "其他渠道"),
            "region", List.of("华东", "华南", "华北", "西南"),
            "merchantType", List.of("零售商户", "餐饮商户", "交通出行", "生活服务"),
            "paymentMethod", List.of("银行卡", "云闪付", "二维码", "其他方式"));

    private final ChatQueryInterpreter interpreter;
    private final CompiledGraph<ChatState> graph;

    public ChatQueryWorkflowService(ChatQueryInterpreter interpreter) throws GraphStateException {
        this.interpreter = interpreter;
        this.graph = new StateGraph<>(
                ChatState.SCHEMA,
                (AgentStateFactory<ChatState>) ChatState::new)
                .addNode("interpretMessage", node_async(this::interpretMessage))
                .addNode("mergeConversationContext", node_async(this::mergeConversationContext))
                .addNode("validateQueryScope", node_async(this::validateQueryScope))
                .addNode("buildSmartBiQueryPlan", node_async(this::buildSmartBiQueryPlan))
                .addNode("executeMockSmartBiQuery", node_async(this::executeMockSmartBiQuery))
                .addNode("generateChatResponse", node_async(this::generateChatResponse))
                .addEdge(START, "interpretMessage")
                .addEdge("interpretMessage", "mergeConversationContext")
                .addEdge("mergeConversationContext", "validateQueryScope")
                .addEdge("validateQueryScope", "buildSmartBiQueryPlan")
                .addEdge("buildSmartBiQueryPlan", "executeMockSmartBiQuery")
                .addEdge("executeMockSmartBiQuery", "generateChatResponse")
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
        InterpretationResult interpretationResult = interpreter.interpret(request, current);
        Interpretation parsed = interpretationResult.interpretation();
        WorkflowStep step = new WorkflowStep(
                "interpretMessage",
                "大模型解析查询意图",
                "COMPLETED",
                interpreter.engineLabel() + " 已识别意图：" + intentName(parsed.intent())
                        + "；仅保留允许的度量、维度和日期");
        return Map.of(
                INTERPRETATION, parsed,
                LLM_MESSAGE, interpretationResult.llmMessage(),
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
        String status;
        String detail;
        if ("OUT_OF_SCOPE".equals(parsed.intent())) {
            status = "rejected";
            detail = "请求超出支付查数范围，后续查询节点将跳过";
        } else if ("GREETING".equals(parsed.intent()) || "RESET".equals(parsed.intent())) {
            status = "clarifying";
            detail = "当前无需取数，等待用户补充查询条件";
        } else if (context.metricIds().isEmpty()) {
            status = "clarifying";
            detail = "缺少度量，请选择交易金额、交易笔数或支付成功率";
        } else {
            status = "completed";
            detail = "校验通过：时间、度量和维度均在白名单内";
        }
        return Map.of(
                STATUS, status,
                STEPS, appendStep(state, new WorkflowStep(
                        "validateQueryScope", "校验查询范围", "COMPLETED", detail)));
    }

    private Map<String, Object> buildSmartBiQueryPlan(ChatState state) {
        if (!"completed".equals(required(state, STATUS))) {
            return Map.of(STEPS, appendStep(state, skipped(
                    "buildSmartBiQueryPlan", "生成 SmartBI 查询计划", "查询条件尚未满足，未生成 JSON")));
        }
        QueryContext context = required(state, CONTEXT);
        List<String> rows = context.dimensionIds().stream()
                .map(id -> DIMENSION_NAMES.get(id) + " (" + DIMENSION_FIELDS.get(id) + ")")
                .toList();
        List<String> columns = context.metricIds().stream()
                .map(id -> METRIC_NAMES.get(id) + " (" + METRIC_FIELDS.get(id) + ")")
                .toList();
        List<String> predicates =
                List.of("trade_date BETWEEN '" + context.startDate() + "' AND '" + context.endDate() + "'");
        ChatQueryPlan plan = new ChatQueryPlan(
                "Mock SmartBI", "payment_query_dataset", rows, columns,
                List.of(new QueryFilter(
                        "交易日期 (trade_date)", "BETWEEN", List.of(context.startDate(), context.endDate()))),
                SmartBiSqlPreview.build(
                        "payment_query_dataset",
                        context.dimensionIds().stream().map(DIMENSION_FIELDS::get).toList(),
                        context.metricIds().stream().map(METRIC_FIELDS::get).toList(),
                        predicates));
        String rowDetail = rows.isEmpty() ? "汇总查询" : "分组：" + String.join("、", rows);
        return Map.of(
                PLAN, plan,
                STEPS, appendStep(state, new WorkflowStep(
                        "buildSmartBiQueryPlan",
                        "生成 SmartBI 查询计划",
                        "COMPLETED",
                        rowDetail + "；度量：" + String.join("、", columns)
                                + "；本步骤已形成可替换真实接口的结构化 JSON")));
    }

    private Map<String, Object> executeMockSmartBiQuery(ChatState state) {
        if (!"completed".equals(required(state, STATUS))) {
            return Map.of(STEPS, appendStep(state, skipped(
                    "executeMockSmartBiQuery", "执行 Mock SmartBI", "未执行数据查询")));
        }
        QueryContext context = required(state, CONTEXT);
        QueryResult result = mockResult(context);
        return Map.of(
                RESULT, result,
                STEPS, appendStep(state, new WorkflowStep(
                        "executeMockSmartBiQuery",
                        "执行 Mock SmartBI",
                        "COMPLETED",
                        "模拟接口一次返回 " + result.rows().size() + " 行、"
                                + result.columns().size() + " 列数据")));
    }

    private Map<String, Object> generateChatResponse(ChatState state) {
        String status = required(state, STATUS);
        QueryContext context = required(state, CONTEXT);
        QueryResult result = "completed".equals(status) ? required(state, RESULT) : null;
        ChatQueryPlan plan = "completed".equals(status) ? required(state, PLAN) : null;
        ChatRequest request = required(state, REQUEST);
        String reply = reply(status, required(state, INTERPRETATION), context, result);
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
                "LangGraph4j → " + interpreter.engineLabel() + " → Mock SmartBI",
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

        List<String> metrics = mergeList(current.metricIds(), parsed.metricIds(), parsed.metricAction());
        List<String> dimensions = mergeList(
                current.dimensionIds(), parsed.dimensionIds(), parsed.dimensionAction());
        if ("QUERY".equals(parsed.intent()) && (startDate.isBlank() || endDate.isBlank())) {
            startDate = "2026-07-01";
            endDate = "2026-07-30";
            periodLabel = "本月（默认）";
        }
        return new QueryContext(startDate, endDate, periodLabel, metrics, dimensions);
    }

    private List<String> mergeList(List<String> current, List<String> incoming, String action) {
        return switch (action) {
            case "CLEAR" -> List.of();
            case "REPLACE", "SET" -> List.copyOf(incoming);
            case "ADD" -> {
                LinkedHashSet<String> values = new LinkedHashSet<>(current);
                values.addAll(incoming);
                yield List.copyOf(values);
            }
            default -> List.copyOf(current);
        };
    }

    private QueryResult mockResult(QueryContext context) {
        List<ResultColumn> columns = new ArrayList<>();
        if (context.dimensionIds().isEmpty()) {
            columns.add(new ResultColumn("period", "时间范围", false));
        } else {
            context.dimensionIds().forEach(id ->
                    columns.add(new ResultColumn(id, DIMENSION_NAMES.get(id), false)));
        }
        context.metricIds().forEach(id ->
                columns.add(new ResultColumn(id, METRIC_NAMES.get(id), true)));

        int rowCount = context.dimensionIds().isEmpty() ? 1 : 4;
        List<Map<String, String>> rows = new ArrayList<>();
        for (int index = 0; index < rowCount; index++) {
            Map<String, String> row = new LinkedHashMap<>();
            if (context.dimensionIds().isEmpty()) {
                row.put("period", context.periodLabel());
            }
            for (String dimensionId : context.dimensionIds()) {
                row.put(dimensionId, DIMENSION_MEMBERS.get(dimensionId).get(index));
            }
            for (String metricId : context.metricIds()) {
                row.put(metricId, metricValue(metricId, index, rowCount));
            }
            rows.add(row);
        }
        String grouping = context.dimensionIds().isEmpty()
                ? "汇总"
                : "按" + names(context.dimensionIds(), DIMENSION_NAMES) + "分组";
        return new QueryResult(
                context.periodLabel() + "，" + grouping + "，"
                        + names(context.metricIds(), METRIC_NAMES),
                List.copyOf(columns),
                List.copyOf(rows));
    }

    private String metricValue(String metricId, int index, int rowCount) {
        return switch (metricId) {
            case "transactionAmount" -> rowCount == 1
                    ? "¥126,840,000"
                    : List.of("¥52,640,000", "¥31,280,000", "¥24,500,000", "¥18,420,000").get(index);
            case "transactionCount" -> rowCount == 1
                    ? "1,286,420"
                    : List.of("526,400", "318,200", "247,900", "193,920").get(index);
            case "successRate" -> rowCount == 1
                    ? "97.36%"
                    : List.of("98.12%", "97.68%", "96.84%", "95.91%").get(index);
            default -> "";
        };
    }

    private String reply(
            String status,
            Interpretation parsed,
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
            return "时间和分组方式已记住，还需要至少一个度量：交易金额、交易笔数或支付成功率。";
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

    private String intentName(String intent) {
        return switch (intent) {
            case "QUERY" -> "支付数据查询";
            case "GREETING" -> "问候";
            case "RESET" -> "重置查询";
            default -> "超出查数范围";
        };
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
        static final Map<String, Channel<?>> SCHEMA = Map.of(
                REQUEST, Channels.base((Supplier<Object>) Map::of),
                CONTEXT, Channels.base((Supplier<Object>) Map::of),
                INTERPRETATION, Channels.base((Supplier<Object>) Map::of),
                STATUS, Channels.base(() -> ""),
                PLAN, Channels.base((Supplier<Object>) Map::of),
                RESULT, Channels.base((Supplier<Object>) Map::of),
                STEPS, Channels.base((Supplier<List<WorkflowStep>>) List::of),
                RESPONSE, Channels.base((Supplier<Object>) Map::of));

        ChatState(Map<String, Object> initData) {
            super(initData);
        }
    }
}
