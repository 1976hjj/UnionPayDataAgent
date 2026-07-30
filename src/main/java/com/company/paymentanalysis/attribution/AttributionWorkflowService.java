package com.company.paymentanalysis.attribution;

import static com.company.paymentanalysis.attribution.AttributionCatalog.DIMENSION_NAMES;
import static com.company.paymentanalysis.attribution.AttributionCatalog.METRIC_NAMES;
import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

import com.company.paymentanalysis.controller.AttributionController.AttributionRequest;
import com.company.paymentanalysis.controller.AttributionController.AttributionResponse;
import com.company.paymentanalysis.controller.AttributionController.ContributionResult;
import com.company.paymentanalysis.controller.AttributionController.DriverMember;
import com.company.paymentanalysis.controller.AttributionController.MemberContribution;
import com.company.paymentanalysis.controller.AttributionController.WorkflowStep;
import com.company.paymentanalysis.smartbi.SmartBiClient;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryRequest;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryResponse;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryTrace;
import com.company.paymentanalysis.smartbi.SmartBiQueryFactory;
import com.company.paymentanalysis.smartbi.SmartBiQueryTranslator;
import com.company.paymentanalysis.smartbi.SmartBiQueryTranslator.TranslationResult;
import com.company.paymentanalysis.smartbi.SmartBiQueryTranslator.TranslatedQueryPlan;
import java.text.DecimalFormat;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
public class AttributionWorkflowService {

    private static final String REQUEST = "request";
    private static final String PLAN = "plan";
    private static final String LLM_MESSAGE = "llmMessage";
    private static final String OVERALL_RESPONSE = "overallResponse";
    private static final String LEVEL1_RESPONSE = "level1Response";
    private static final String DRIVER = "driver";
    private static final String LEVEL2_RESULTS = "level2Results";
    private static final String TRACES = "traces";
    private static final String RESULT = "result";

    private final SmartBiQueryTranslator translator;
    private final SmartBiQueryFactory queryFactory;
    private final SmartBiClient smartBiClient;
    private final CompiledGraph<AttributionState> graph;

    public AttributionWorkflowService(
            SmartBiQueryTranslator translator,
            SmartBiQueryFactory queryFactory,
            SmartBiClient smartBiClient) throws GraphStateException {
        this.translator = translator;
        this.queryFactory = queryFactory;
        this.smartBiClient = smartBiClient;
        this.graph = new StateGraph<>(
                AttributionState.SCHEMA,
                (AgentStateFactory<AttributionState>) AttributionState::new)
                .addNode("translateChineseValues", node_async(this::translateChineseValues))
                .addNode("executeOverallQuery", node_async(this::executeOverallQuery))
                .addNode("executeLevel1Query", node_async(this::executeLevel1Query))
                .addNode("selectLevel1Driver", node_async(this::selectLevel1Driver))
                .addNode("executeParallelLevel2Queries", node_async(this::executeParallelLevel2Queries))
                .addNode("generateAttributionReport", node_async(this::generateAttributionReport))
                .addEdge(START, "translateChineseValues")
                .addEdge("translateChineseValues", "executeOverallQuery")
                .addEdge("executeOverallQuery", "executeLevel1Query")
                .addEdge("executeLevel1Query", "selectLevel1Driver")
                .addEdge("selectLevel1Driver", "executeParallelLevel2Queries")
                .addEdge("executeParallelLevel2Queries", "generateAttributionReport")
                .addEdge("generateAttributionReport", END)
                .compile();
    }

    public AttributionResponse analyze(AttributionRequest request) {
        return graph.invoke(Map.of(REQUEST, request, TRACES, List.of()))
                .flatMap(state -> state.<AttributionResponse>value(RESULT))
                .orElseThrow(() -> new IllegalStateException("LangGraph4j 未生成归因结果"));
    }

    private Map<String, Object> translateChineseValues(AttributionState state) {
        TranslationResult result = translator.translate(required(state, REQUEST));
        return Map.of(PLAN, result.plan(), LLM_MESSAGE, result.llmMessage());
    }

    private Map<String, Object> executeOverallQuery(AttributionState state) {
        AttributionRequest request = required(state, REQUEST);
        TranslatedQueryPlan plan = required(state, PLAN);
        QueryRequest query = queryFactory.create(request, plan, null, null);
        return Map.of(
                OVERALL_RESPONSE, smartBiClient.query(query),
                TRACES, appendTrace(state, new QueryTrace("overall", null, query)));
    }

    private Map<String, Object> executeLevel1Query(AttributionState state) {
        AttributionRequest request = required(state, REQUEST);
        TranslatedQueryPlan plan = required(state, PLAN);
        QueryRequest query = queryFactory.create(request, plan, request.level1DimensionCode(), null);
        return Map.of(
                LEVEL1_RESPONSE, smartBiClient.query(query),
                TRACES, appendTrace(state, new QueryTrace("level1", request.level1DimensionCode(), query)));
    }

    private Map<String, Object> selectLevel1Driver(AttributionState state) {
        QueryResponse response = required(state, LEVEL1_RESPONSE);
        Map<String, Object> selected = response.data().stream()
                .filter(row -> "UP".equals(row.get("direction")))
                .max((left, right) -> Double.compare(
                        Math.abs(number(left, "changeAmount")),
                        Math.abs(number(right, "changeAmount"))))
                .orElseThrow(() -> new IllegalStateException("一级查询未返回与整体方向一致的成员"));
        return Map.of(DRIVER, new DriverMember(
                String.valueOf(selected.get("memberCode")),
                String.valueOf(selected.get("memberName")),
                signedNumber(number(selected, "changeAmount")),
                String.valueOf(selected.get("direction")),
                "与整体变化方向一致，且绝对变化额最大"));
    }

    private Map<String, Object> executeParallelLevel2Queries(AttributionState state) {
        AttributionRequest request = required(state, REQUEST);
        TranslatedQueryPlan plan = required(state, PLAN);
        DriverMember driver = required(state, DRIVER);
        Map<String, ContributionResult> results = new LinkedHashMap<>();
        List<QueryTrace> traces = new ArrayList<>(state.<List<QueryTrace>>value(TRACES).orElseGet(List::of));

        for (String dimensionCode : request.level2DimensionCodes()) {
            QueryRequest query = queryFactory.create(request, plan, dimensionCode, driver.memberName());
            QueryResponse response = smartBiClient.query(query);
            results.put(
                    dimensionCode,
                    new ContributionResult(
                            dimensionCode,
                            DIMENSION_NAMES.get(dimensionCode),
                            response.data().stream()
                                    .map(row -> toContribution(request.metricCode(), row))
                                    .toList()));
            traces.add(new QueryTrace("level2", dimensionCode, query));
        }
        return Map.of(LEVEL2_RESULTS, results, TRACES, List.copyOf(traces));
    }

    private Map<String, Object> generateAttributionReport(AttributionState state) {
        AttributionRequest request = required(state, REQUEST);
        YearMonth current = YearMonth.parse(request.currentPeriod());
        YearMonth comparison = "yearOnYear".equals(request.comparisonType())
                ? current.minusYears(1)
                : current.minusMonths(1);
        QueryResponse overall = required(state, OVERALL_RESPONSE);
        double overallChange = number(overall.data().get(0), "overallChangeRate");
        Map<String, ContributionResult> level2Results = required(state, LEVEL2_RESULTS);
        List<QueryTrace> traces = state.<List<QueryTrace>>value(TRACES).orElseGet(List::of);

        return Map.of(RESULT, new AttributionResponse(
                METRIC_NAMES.get(request.metricCode()),
                formatPeriod(current),
                formatPeriod(comparison),
                String.format("%+.1f%%", overallChange),
                request.level1DimensionCode(),
                DIMENSION_NAMES.get(request.level1DimensionCode()),
                required(state, DRIVER),
                level2Results,
                traces.size(),
                true,
                "多个二级维度是同一批数据的不同观察角度，各维度贡献不能相互累加。",
                "LangGraph4j → " + translator.engineLabel() + " → Mock SmartBI",
                workflowSteps(request, traces),
                traces,
                required(state, LLM_MESSAGE)));
    }

    private List<WorkflowStep> workflowSteps(AttributionRequest request, List<QueryTrace> traces) {
        return List.of(
                new WorkflowStep(
                        "translateChineseValues",
                        "中文值转换",
                        "COMPLETED",
                        translator.engineLabel() + " 已生成 SmartBI 字段映射 JSON"),
                new WorkflowStep(
                        "executeOverallQuery",
                        "整体查询",
                        "COMPLETED",
                        "本期与对比期合并为 1 次 SmartBI 查询"),
                new WorkflowStep(
                        "executeLevel1Query",
                        "一级维度查询",
                        "COMPLETED",
                        DIMENSION_NAMES.get(request.level1DimensionCode()) + " 已返回成员变化"),
                new WorkflowStep(
                        "selectLevel1Driver",
                        "选择一级驱动",
                        "COMPLETED",
                        "已选择与整体同方向且绝对变化额最大的 1 个成员"),
                new WorkflowStep(
                        "executeParallelLevel2Queries",
                        "二级并行查询",
                        "COMPLETED",
                        request.level2DimensionCodes().size() + " 个维度分别执行，累计 "
                                + traces.size() + " 次 SmartBI 查询"),
                new WorkflowStep(
                        "generateAttributionReport",
                        "生成归因报告",
                        "COMPLETED",
                        "已汇总并行视角，贡献度不跨维度累加"));
    }

    private MemberContribution toContribution(String metricCode, Map<String, Object> row) {
        double current = number(row, "currentValue");
        double comparison = number(row, "comparisonValue");
        double change = number(row, "changeAmount");
        return new MemberContribution(
                String.valueOf(row.get("memberName")),
                formatMetric(metricCode, current),
                formatMetric(metricCode, comparison),
                formatChange(metricCode, change),
                String.format("%+.1f%%", number(row, "changeRate")),
                number(row, "contributionRate"),
                String.valueOf(row.get("direction")));
    }

    private String formatMetric(String metricCode, double value) {
        return switch (metricCode) {
            case "transactionCount" -> new DecimalFormat("#,##0").format(value) + "笔";
            case "successRate" -> new DecimalFormat("0.00").format(value) + "%";
            default -> "¥" + new DecimalFormat("#,##0").format(value);
        };
    }

    private String formatChange(String metricCode, double value) {
        String sign = value >= 0 ? "+" : "-";
        double absolute = Math.abs(value);
        return switch (metricCode) {
            case "transactionCount" -> sign + new DecimalFormat("#,##0").format(absolute) + "笔";
            case "successRate" -> sign + new DecimalFormat("0.00").format(absolute) + "pp";
            default -> sign + "¥" + new DecimalFormat("#,##0").format(absolute);
        };
    }

    private String signedNumber(double value) {
        return (value >= 0 ? "+" : "-") + new DecimalFormat("#,##0.00").format(Math.abs(value));
    }

    private double number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).doubleValue();
    }

    private String formatPeriod(YearMonth period) {
        return period.format(DateTimeFormatter.ofPattern("yyyy年M月"));
    }

    private List<QueryTrace> appendTrace(AttributionState state, QueryTrace trace) {
        List<QueryTrace> traces = new ArrayList<>(state.<List<QueryTrace>>value(TRACES).orElseGet(List::of));
        traces.add(trace);
        return List.copyOf(traces);
    }

    private <T> T required(AttributionState state, String key) {
        return state.<T>value(key).orElseThrow(() -> new IllegalStateException("LangGraph4j 状态缺少 " + key));
    }

    static final class AttributionState extends AgentState {
        static final Map<String, Channel<?>> SCHEMA = Map.of(
                REQUEST, Channels.base((Supplier<Object>) Map::of),
                PLAN, Channels.base((Supplier<Object>) Map::of),
                OVERALL_RESPONSE, Channels.base((Supplier<Object>) Map::of),
                LEVEL1_RESPONSE, Channels.base((Supplier<Object>) Map::of),
                DRIVER, Channels.base((Supplier<Object>) Map::of),
                LEVEL2_RESULTS, Channels.base((Supplier<Object>) Map::of),
                TRACES, Channels.base((Supplier<List<QueryTrace>>) List::of),
                RESULT, Channels.base((Supplier<Object>) Map::of));

        AttributionState(Map<String, Object> initData) {
            super(initData);
        }
    }
}
