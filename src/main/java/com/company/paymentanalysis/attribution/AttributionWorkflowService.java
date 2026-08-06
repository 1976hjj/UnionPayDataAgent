package com.company.paymentanalysis.attribution;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

import com.company.paymentanalysis.attribution.AttributionCatalog.AttributionDimension;
import com.company.paymentanalysis.attribution.AttributionModels.AttributionResponse;
import com.company.paymentanalysis.attribution.AttributionModels.DimensionFilter;
import com.company.paymentanalysis.attribution.AttributionModels.EffectiveRequest;
import com.company.paymentanalysis.attribution.AttributionModels.Evidence;
import com.company.paymentanalysis.attribution.AttributionModels.MemberEvidence;
import com.company.paymentanalysis.attribution.AttributionModels.OverallEvidence;
import com.company.paymentanalysis.attribution.AttributionModels.PathNode;
import com.company.paymentanalysis.attribution.AttributionModels.ReasoningStep;
import com.company.paymentanalysis.attribution.AttributionModels.StopInfo;
import com.company.paymentanalysis.attribution.AttributionModels.WorkflowStep;
import com.company.paymentanalysis.attribution.AttributionQueryService.QueryExecution;
import com.company.paymentanalysis.attribution.AttributionReasoner.NextDecision;
import com.company.paymentanalysis.attribution.AttributionReasoner.PlanDecision;
import com.company.paymentanalysis.attribution.AttributionReasoner.ReportDecision;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryTrace;
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
public class AttributionWorkflowService {

    private static final String REQUEST = "request";
    private static final String OVERALL = "overall";
    private static final String SCOPE_OVERALL = "scopeOverall";
    private static final String DEPTH = "depth";
    private static final String QUERY_COUNT = "queryCount";
    private static final String PLANNED_DIMENSIONS = "plannedDimensions";
    private static final String HYPOTHESIS = "hypothesis";
    private static final String PATH_FILTERS = "pathFilters";
    private static final String PENDING = "pending";
    private static final String EVIDENCE = "evidence";
    private static final String PATH = "path";
    private static final String REASONING = "reasoning";
    private static final String TRACES = "traces";
    private static final String STEPS = "steps";
    private static final String DECISION = "decision";
    private static final String STOP = "stop";
    private static final String ROUTE = "route";
    private static final String RESULT = "result";

    private final AttributionQueryService queryService;
    private final AttributionEvidenceCalculator calculator;
    private final AttributionReasoner reasoner;
    private final CompiledGraph<AttributionState> graph;

    public AttributionWorkflowService(
            AttributionQueryService queryService,
            AttributionEvidenceCalculator calculator,
            AttributionReasoner reasoner) throws GraphStateException {
        this.queryService = queryService;
        this.calculator = calculator;
        this.reasoner = reasoner;
        this.graph = new StateGraph<>(AttributionState.SCHEMA, (AgentStateFactory<AttributionState>) AttributionState::new)
                .addNode("initialize", node_async(this::initialize))
                .addNode("planExploration", node_async(this::planExploration))
                .addNode("executeQueries", node_async(this::executeQueries))
                .addNode("computeEvidence", node_async(this::computeEvidence))
                .addNode("reasonEvidence", node_async(this::reasonEvidence))
                .addNode("prepareNext", node_async(this::prepareNext))
                .addNode("generateReport", node_async(this::generateReport))
                .addEdge(START, "initialize")
                .addEdge("initialize", "planExploration")
                .addConditionalEdges(
                        "planExploration",
                        edge_async(state -> state.<String>value(ROUTE).orElse("stop")),
                        Map.of("stop", "generateReport", "continue", "executeQueries"))
                .addEdge("executeQueries", "computeEvidence")
                .addEdge("computeEvidence", "reasonEvidence")
                .addConditionalEdges(
                        "reasonEvidence",
                        edge_async(state -> state.<String>value(ROUTE).orElse("stop")),
                        Map.of("stop", "generateReport", "continue", "prepareNext"))
                .addEdge("prepareNext", "executeQueries")
                .addEdge("generateReport", END)
                .compile();
    }

    public AttributionResponse analyze(EffectiveRequest request) {
        return graph.invoke(Map.of(
                        REQUEST, request,
                        DEPTH, 1,
                        QUERY_COUNT, 0,
                        PATH_FILTERS, List.of(),
                        EVIDENCE, List.of(),
                        PATH, List.of(),
                        REASONING, List.of(),
                        TRACES, List.of(),
                        STEPS, List.of()))
                .flatMap(state -> state.<AttributionResponse>value(RESULT))
                .orElseThrow(() -> new IllegalStateException("LangGraph4j 未生成归因结果"));
    }

    private Map<String, Object> initialize(AttributionState state) {
        EffectiveRequest request = required(state, REQUEST);
        QueryExecution execution = queryService.queryOverall(request);
        OverallEvidence overall = calculator.overall(request, execution.response());
        return Map.of(
                OVERALL, overall,
                SCOPE_OVERALL, overall,
                QUERY_COUNT, 1,
                TRACES, List.of(execution.trace()),
                STEPS, appendStep(state, step("initialize", "初始化整体变化", "已查询当前周期和对比周期")));
    }

    private Map<String, Object> planExploration(AttributionState state) {
        EffectiveRequest request = required(state, REQUEST);
        List<AttributionDimension> candidates = remainingDimensions(request, state);
        int maxDimensions = Math.min(3, request.maxQueries() - number(state, QUERY_COUNT));
        if (maxDimensions <= 0) {
            return Map.of(
                    STOP, new StopInfo("NO_QUERY_BUDGET", "整体查询后没有剩余查询额度"),
                    ROUTE, "stop",
                    STEPS, appendStep(state, step("planExploration", "规划首轮探索", "没有可用查询额度")));
        }
        if (candidates.isEmpty()) {
            return Map.of(
                    STOP, new StopInfo("NO_DIMENSIONS", "请求过滤已占用全部允许归因的维度"),
                    ROUTE, "stop",
                    STEPS, appendStep(state, step("planExploration", "规划首轮探索", "没有可探索的归因维度")));
        }
        PlanDecision plan = reasoner.plan(request, candidates, maxDimensions);
        validateDimensions(plan.dimensions(), candidates);
        ReasoningStep reasoning = new ReasoningStep(
                1, "PLAN", plan.hypothesis(), plan.dimensions(), null, null, null, plan.reason(), plan.llmMessage());
        return Map.of(
                PLANNED_DIMENSIONS, plan.dimensions(),
                HYPOTHESIS, plan.hypothesis(),
                ROUTE, "continue",
                REASONING, appendReasoning(state, reasoning),
                STEPS, appendStep(state, step(
                        "planExploration", "规划首轮探索", "选择维度：" + String.join("、", plan.dimensions()))));
    }

    private Map<String, Object> executeQueries(AttributionState state) {
        EffectiveRequest request = required(state, REQUEST);
        int depth = number(state, DEPTH);
        int queryCount = number(state, QUERY_COUNT);
        List<QueryExecution> pending = new ArrayList<>();
        List<QueryTrace> traces = new ArrayList<>(list(state, TRACES));
        for (String dimension : this.<String>list(state, PLANNED_DIMENSIONS)) {
            if (queryCount >= request.maxQueries()) {
                break;
            }
            QueryExecution execution = queryService.queryDimension(
                    request, dimension, list(state, PATH_FILTERS), depth);
            pending.add(execution);
            traces.add(execution.trace());
            queryCount++;
        }
        if (pending.isEmpty()) {
            throw new IllegalStateException("归因查询计划没有执行任何查询");
        }
        return Map.of(
                PENDING, List.copyOf(pending),
                QUERY_COUNT, queryCount,
                TRACES, List.copyOf(traces),
                STEPS, appendStep(state, step(
                        "executeQueries", "执行 SmartBI 查询", "第 " + depth + " 层执行 " + pending.size() + " 次查询")));
    }

    private Map<String, Object> computeEvidence(AttributionState state) {
        EffectiveRequest request = required(state, REQUEST);
        OverallEvidence scopeOverall = required(state, SCOPE_OVERALL);
        int depth = number(state, DEPTH);
        String hypothesis = required(state, HYPOTHESIS);
        List<Evidence> evidence = new ArrayList<>(list(state, EVIDENCE));
        for (QueryExecution execution : this.<QueryExecution>list(state, PENDING)) {
            String dimension = execution.trace().dimensionCode();
            evidence.add(calculator.evidence(
                    request,
                    scopeOverall,
                    hypothesis,
                    dimension,
                    depth,
                    list(state, PATH_FILTERS),
                    execution.response()));
        }
        return Map.of(
                EVIDENCE, List.copyOf(evidence),
                STEPS, appendStep(state, step(
                        "computeEvidence", "计算归因证据", "Java 已完成变化额、贡献度、排名和一致性校验")));
    }

    private Map<String, Object> reasonEvidence(AttributionState state) {
        EffectiveRequest request = required(state, REQUEST);
        int depth = number(state, DEPTH);
        StopInfo deterministicStop = deterministicStop(request, state, depth);
        if (deterministicStop != null) {
            return Map.of(
                    STOP, deterministicStop,
                    ROUTE, "stop",
                    STEPS, appendStep(state, step("reasonEvidence", "判断是否继续", deterministicStop.detail())));
        }

        List<AttributionDimension> remaining = remainingDimensions(request, state);
        NextDecision decision = reasoner.next(
                request, required(state, OVERALL), list(state, EVIDENCE), remaining, depth);
        ReasoningStep reasoning = new ReasoningStep(
                depth,
                "REASON",
                decision.hypothesis(),
                List.of(),
                decision.selectedEvidenceId(),
                decision.selectedMember(),
                decision.nextDimension(),
                decision.reason(),
                decision.llmMessage());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(REASONING, appendReasoning(state, reasoning));
        result.put(STEPS, appendStep(state, step("reasonEvidence", "判断是否继续", decision.reason())));
        if (!decision.shouldContinue()) {
            result.put(STOP, new StopInfo("LLM_STOP", decision.reason()));
            result.put(ROUTE, "stop");
        } else {
            validateNextDecision(decision, state, remaining, depth);
            result.put(DECISION, decision);
            result.put(ROUTE, "continue");
        }
        return Map.copyOf(result);
    }

    private Map<String, Object> prepareNext(AttributionState state) {
        NextDecision decision = required(state, DECISION);
        int depth = number(state, DEPTH);
        Evidence selectedEvidence = evidenceAtDepth(state, decision.selectedEvidenceId(), depth);
        MemberEvidence selectedMember = selectedEvidence.members().stream()
                .filter(member -> member.memberValue().equals(decision.selectedMember()))
                .findFirst()
                .orElseThrow();

        List<DimensionFilter> pathFilters = new ArrayList<>(list(state, PATH_FILTERS));
        pathFilters.add(new DimensionFilter(
                selectedEvidence.dimensionId(), "EQUALS", List.of(selectedMember.memberValue())));
        List<PathNode> path = new ArrayList<>(list(state, PATH));
        path.add(new PathNode(
                depth,
                selectedEvidence.dimensionId(),
                selectedEvidence.dimensionName(),
                selectedMember.memberValue(),
                selectedMember.changeAmount(),
                selectedMember.contributionRate()));
        OverallEvidence scope = new OverallEvidence(
                selectedMember.currentValue(),
                selectedMember.comparisonValue(),
                selectedMember.changeAmount(),
                selectedMember.changeRate(),
                null,
                selectedMember.direction());
        return Map.of(
                DEPTH, depth + 1,
                PATH_FILTERS, List.copyOf(pathFilters),
                PATH, List.copyOf(path),
                SCOPE_OVERALL, scope,
                PLANNED_DIMENSIONS, List.of(decision.nextDimension()),
                HYPOTHESIS, decision.hypothesis(),
                STEPS, appendStep(state, step(
                        "prepareNext", "形成下一层假设", selectedMember.memberValue() + " → " + decision.nextDimension())));
    }

    private Map<String, Object> generateReport(AttributionState state) {
        EffectiveRequest request = required(state, REQUEST);
        StopInfo stop = state.<StopInfo>value(STOP)
                .orElseGet(() -> new StopInfo("COMPLETED", "归因分析完成"));
        List<PathNode> path = completePath(state);
        ReportDecision report = reasoner.report(
                request, required(state, OVERALL), list(state, EVIDENCE), path, stop);
        List<ReasoningStep> reasoning = new ArrayList<>(list(state, REASONING));
        reasoning.add(new ReasoningStep(
                number(state, DEPTH), "REPORT", report.report().summary(), List.of(), null, null, null,
                "根据已验证 Evidence 组织报告", report.llmMessage()));
        List<WorkflowStep> steps = appendStep(state, step("generateReport", "生成归因报告", stop.detail()));
        AttributionResponse response = new AttributionResponse(
                "completed",
                request.metricId(),
                AttributionCatalog.metricName(request.metricId()),
                request.currentPeriod(),
                request.comparisonPeriod(),
                required(state, OVERALL),
                list(state, EVIDENCE),
                path,
                List.copyOf(reasoning),
                stop,
                report.report(),
                number(state, QUERY_COUNT),
                "LangGraph4j → " + reasoner.engineLabel(request.model()) + " → SmartBI Client",
                steps,
                list(state, TRACES));
        return Map.of(RESULT, response, REASONING, List.copyOf(reasoning), STEPS, steps);
    }

    private StopInfo deterministicStop(EffectiveRequest request, AttributionState state, int depth) {
        if (depth >= request.maxDepth()) {
            return new StopInfo("MAX_DEPTH", "已达到最大下钻深度 " + request.maxDepth());
        }
        if (number(state, QUERY_COUNT) >= request.maxQueries()) {
            return new StopInfo("MAX_QUERIES", "已达到最大查询次数 " + request.maxQueries());
        }
        List<Evidence> current = this.<Evidence>list(state, EVIDENCE).stream()
                .filter(item -> item.depth() == depth)
                .toList();
        boolean hasDriver = current.stream()
                .map(Evidence::primaryDriver)
                .anyMatch(driver -> driver != null
                        && driver.alignedWithOverall()
                        && driver.contributionRate().abs().compareTo(new BigDecimal("10")) >= 0);
        if (!hasDriver) {
            return new StopInfo("LOW_SIGNAL", "没有贡献度绝对值达到 10% 的同方向驱动成员");
        }
        if (remainingDimensions(request, state).isEmpty()) {
            return new StopInfo("NO_DIMENSIONS", "没有尚未探索的合法归因维度");
        }
        return null;
    }

    private void validateNextDecision(
            NextDecision decision,
            AttributionState state,
            List<AttributionDimension> remaining,
            int depth) {
        Evidence evidence = evidenceAtDepth(state, decision.selectedEvidenceId(), depth);
        MemberEvidence member = evidence.members().stream()
                .filter(item -> item.memberValue().equals(decision.selectedMember()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("LLM 选择了 Evidence 中不存在的成员"));
        if (!member.alignedWithOverall()) {
            throw new IllegalStateException("LLM 选择的成员方向与当前分析范围不一致");
        }
        if (evidence.dimensionId().equals(decision.nextDimension())) {
            throw new IllegalStateException("下一层不能重复当前驱动维度");
        }
        if (remaining.stream().noneMatch(item -> item.id().equals(decision.nextDimension()))) {
            throw new IllegalStateException("LLM 选择了非法、重复或不可用的下一层维度");
        }
    }

    private Evidence evidenceAtDepth(AttributionState state, String id, int depth) {
        return this.<Evidence>list(state, EVIDENCE).stream()
                .filter(item -> item.depth() == depth && item.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("LLM 选择了不存在或层级不匹配的 Evidence"));
    }

    private List<PathNode> completePath(AttributionState state) {
        List<PathNode> path = new ArrayList<>(list(state, PATH));
        int depth = number(state, DEPTH);
        this.<Evidence>list(state, EVIDENCE).stream()
                .filter(item -> item.depth() == depth && item.primaryDriver() != null)
                .filter(item -> item.primaryDriver().alignedWithOverall())
                .max((left, right) -> left.primaryDriver().contributionRate().abs()
                        .compareTo(right.primaryDriver().contributionRate().abs()))
                .ifPresent(item -> path.add(new PathNode(
                        depth,
                        item.dimensionId(),
                        item.dimensionName(),
                        item.primaryDriver().memberValue(),
                        item.primaryDriver().changeAmount(),
                        item.primaryDriver().contributionRate())));
        return List.copyOf(path);
    }

    private List<AttributionDimension> remainingDimensions(EffectiveRequest request, AttributionState state) {
        Set<String> excluded = new LinkedHashSet<>();
        request.dimensionFilters().forEach(filter -> excluded.add(filter.dimensionId()));
        this.<PathNode>list(state, PATH).forEach(node -> excluded.add(node.dimensionId()));
        return AttributionCatalog.dimensions().stream()
                .filter(dimension -> !excluded.contains(dimension.id()))
                .toList();
    }

    private void validateDimensions(List<String> requested, List<AttributionDimension> candidates) {
        Set<String> allowed = candidates.stream().map(AttributionDimension::id).collect(java.util.stream.Collectors.toSet());
        if (requested == null || requested.isEmpty() || new LinkedHashSet<>(requested).size() != requested.size()
                || requested.stream().anyMatch(id -> !allowed.contains(id))) {
            throw new IllegalStateException("LLM 返回了非法或重复的归因维度");
        }
    }

    private WorkflowStep step(String node, String name, String detail) {
        return new WorkflowStep(node, name, "COMPLETED", detail);
    }

    private List<WorkflowStep> appendStep(AttributionState state, WorkflowStep step) {
        List<WorkflowStep> steps = new ArrayList<>(list(state, STEPS));
        steps.add(step);
        return List.copyOf(steps);
    }

    private List<ReasoningStep> appendReasoning(AttributionState state, ReasoningStep reasoning) {
        List<ReasoningStep> items = new ArrayList<>(list(state, REASONING));
        items.add(reasoning);
        return List.copyOf(items);
    }

    private int number(AttributionState state, String key) {
        return state.<Integer>value(key).orElse(0);
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> list(AttributionState state, String key) {
        return state.<List<T>>value(key).orElseGet(List::of);
    }

    private <T> T required(AttributionState state, String key) {
        return state.<T>value(key).orElseThrow(() -> new IllegalStateException("归因 State 缺少 " + key));
    }

    static final class AttributionState extends AgentState {
        static final Map<String, Channel<?>> SCHEMA = Map.ofEntries(
                Map.entry(REQUEST, Channels.base((Supplier<Object>) Map::of)),
                Map.entry(OVERALL, Channels.base((Supplier<Object>) Map::of)),
                Map.entry(SCOPE_OVERALL, Channels.base((Supplier<Object>) Map::of)),
                Map.entry(DEPTH, Channels.base(() -> 1)),
                Map.entry(QUERY_COUNT, Channels.base(() -> 0)),
                Map.entry(PLANNED_DIMENSIONS, Channels.base((Supplier<List<String>>) List::of)),
                Map.entry(HYPOTHESIS, Channels.base(() -> "")),
                Map.entry(PATH_FILTERS, Channels.base((Supplier<List<DimensionFilter>>) List::of)),
                Map.entry(PENDING, Channels.base((Supplier<List<QueryExecution>>) List::of)),
                Map.entry(EVIDENCE, Channels.base((Supplier<List<Evidence>>) List::of)),
                Map.entry(PATH, Channels.base((Supplier<List<PathNode>>) List::of)),
                Map.entry(REASONING, Channels.base((Supplier<List<ReasoningStep>>) List::of)),
                Map.entry(TRACES, Channels.base((Supplier<List<QueryTrace>>) List::of)),
                Map.entry(STEPS, Channels.base((Supplier<List<WorkflowStep>>) List::of)),
                Map.entry(DECISION, Channels.base((Supplier<Object>) Map::of)),
                Map.entry(STOP, Channels.base((Supplier<Object>) Map::of)),
                Map.entry(ROUTE, Channels.base(() -> "stop")),
                Map.entry(RESULT, Channels.base((Supplier<Object>) Map::of)));

        AttributionState(Map<String, Object> initData) {
            super(initData);
        }
    }
}
