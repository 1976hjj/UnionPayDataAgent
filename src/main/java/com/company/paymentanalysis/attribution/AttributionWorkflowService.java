package com.company.paymentanalysis.attribution;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

import com.company.paymentanalysis.attribution.AttributionCatalog.AttributionDimension;
import com.company.paymentanalysis.attribution.AttributionModels.AnalysisBranch;
import com.company.paymentanalysis.attribution.AttributionModels.AttributionResponse;
import com.company.paymentanalysis.attribution.AttributionModels.BranchAction;
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
import com.company.paymentanalysis.attribution.AttributionReasoner.PlanDecision;
import com.company.paymentanalysis.attribution.AttributionReasoner.ReflectionDecision;
import com.company.paymentanalysis.attribution.AttributionReasoner.ReportDecision;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryTrace;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.AgentStateFactory;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;
import org.springframework.stereotype.Service;

/**
 * Bounded attribution exploration. The LLM proposes semantic branches; Java owns all
 * query-budget, whitelist, path-validity, duplicate and information-gain decisions.
 */
@Service
public class AttributionWorkflowService {

    private static final String REQUEST = "request";
    private static final String OVERALL = "overall";
    private static final String QUERY_COUNT = "queryCount";
    private static final String PLANNED_WORK = "plannedWork";
    private static final String PENDING = "pending";
    private static final String CURRENT_EVIDENCE = "currentEvidence";
    private static final String EVIDENCE = "evidence";
    private static final String BRANCHES = "branches";
    private static final String VISITED = "visited";
    private static final String REASONING = "reasoning";
    private static final String TRACES = "traces";
    private static final String STEPS = "steps";
    private static final String DECISION = "decision";
    private static final String STOP = "stop";
    private static final String ROUTE = "route";
    private static final String RESULT = "result";
    private static final String OBSERVER_ID = "observerId";
    private static final Map<String, WorkflowObserver> OBSERVERS = new ConcurrentHashMap<>();

    private final AttributionQueryService queryService;
    private final AttributionEvidenceCalculator calculator;
    private final AttributionReasoner reasoner;
    private final AttributionPolicyProperties policy;
    private final CompiledGraph<AttributionState> graph;

    public AttributionWorkflowService(
            AttributionQueryService queryService,
            AttributionEvidenceCalculator calculator,
            AttributionReasoner reasoner,
            AttributionPolicyProperties policy) throws GraphStateException {
        this.queryService = queryService;
        this.calculator = calculator;
        this.reasoner = reasoner;
        this.policy = policy;
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
                .addConditionalEdges("planExploration", edge_async(state -> state.<String>value(ROUTE).orElse("stop")),
                        Map.of("stop", "generateReport", "continue", "executeQueries"))
                .addEdge("executeQueries", "computeEvidence")
                .addEdge("computeEvidence", "reasonEvidence")
                .addConditionalEdges("reasonEvidence", edge_async(state -> state.<String>value(ROUTE).orElse("stop")),
                        Map.of("stop", "generateReport", "continue", "prepareNext"))
                .addConditionalEdges("prepareNext", edge_async(state -> state.<String>value(ROUTE).orElse("stop")),
                        Map.of("stop", "generateReport", "continue", "executeQueries"))
                .addEdge("generateReport", END)
                .compile();
    }

    public AttributionResponse analyze(EffectiveRequest request) {
        return analyze(request, event -> { });
    }

    public AttributionResponse analyze(EffectiveRequest request, WorkflowObserver observer) {
        WorkflowObserver effectiveObserver = observer == null ? event -> { } : observer;
        String observerId = UUID.randomUUID().toString();
        OBSERVERS.put(observerId, effectiveObserver);
        try {
            return graph.invoke(Map.ofEntries(
                            Map.entry(REQUEST, request),
                            Map.entry(OBSERVER_ID, observerId),
                            Map.entry(QUERY_COUNT, 0),
                            Map.entry(PLANNED_WORK, List.of()),
                            Map.entry(CURRENT_EVIDENCE, List.of()),
                            Map.entry(EVIDENCE, List.of()),
                            Map.entry(BRANCHES, List.of()),
                            Map.entry(VISITED, Set.of()),
                            Map.entry(REASONING, List.of()),
                            Map.entry(TRACES, List.of()),
                            Map.entry(STEPS, List.of())))
                    .flatMap(state -> state.<AttributionResponse>value(RESULT))
                    .orElseThrow(() -> new IllegalStateException("LangGraph4j did not produce an attribution result"));
        } finally {
            OBSERVERS.remove(observerId);
        }
    }

    private Map<String, Object> initialize(AttributionState state) {
        emit(state, "initialize", "初始化整体变化", "RUNNING", "正在查询整体当前周期和对比周期", null);
        EffectiveRequest request = required(state, REQUEST);
        QueryExecution execution = queryService.queryOverall(request);
        OverallEvidence overall = calculator.overall(request, execution.response());
        String detail = "已查询当前周期和对比周期";
        emit(state, "initialize", "初始化整体变化", "COMPLETED", detail, null);
        return Map.of(
                OVERALL, overall,
                QUERY_COUNT, 1,
                TRACES, List.of(execution.trace()),
                STEPS, appendStep(state, step("initialize", "初始化整体变化", detail)));
    }

    private Map<String, Object> planExploration(AttributionState state) {
        emit(state, "planExploration", "规划首轮探索", "RUNNING", "正在请求 LLM 选择首轮互补维度", null);
        EffectiveRequest request = required(state, REQUEST);
        List<AttributionDimension> candidates = remainingDimensions(request, List.of());
        int availableQueries = request.maxQueries() - number(state, QUERY_COUNT);
        int maxDimensions = Math.min(policy.maxInitialDimensions(), Math.max(0, availableQueries));
        if (maxDimensions <= 0) {
            return stop(state, "planExploration", "规划首轮探索", "NO_QUERY_BUDGET", "整体查询后没有可用查询额度");
        }
        if (candidates.isEmpty()) {
            return stop(state, "planExploration", "规划首轮探索", "NO_DIMENSIONS", "请求过滤已占用全部允许归因的维度");
        }
        PlanDecision plan = reasoner.plan(request, candidates, maxDimensions);
        validateDimensions(plan.dimensions(), candidates);
        List<WorkItem> work = plan.dimensions().stream()
                .map(dimension -> WorkItem.root(dimension, plan.hypothesis(), required(state, OVERALL)))
                .toList();
        Set<String> visited = work.stream().map(WorkItem::signature).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        ReasoningStep reasoning = new ReasoningStep(
                1, "PLAN", plan.hypothesis(), plan.dimensions(), null, null, null, plan.reason(), List.of(), plan.llmMessage());
        String detail = "选择维度：" + String.join("、", plan.dimensions());
        emit(state, "planExploration", "规划首轮探索", "COMPLETED", detail, reasoning);
        return Map.of(
                PLANNED_WORK, work,
                VISITED, Set.copyOf(visited),
                ROUTE, "continue",
                REASONING, appendReasoning(state, reasoning),
                STEPS, appendStep(state, step("planExploration", "规划首轮探索", detail)));
    }

    private Map<String, Object> executeQueries(AttributionState state) {
        emit(state, "executeQueries", "执行 SmartBI 查询", "RUNNING", "正在执行本轮获批分支查询", null);
        EffectiveRequest request = required(state, REQUEST);
        int queryCount = number(state, QUERY_COUNT);
        List<WorkExecution> pending = new ArrayList<>();
        List<QueryTrace> traces = new ArrayList<>(list(state, TRACES));
        for (WorkItem work : this.<WorkItem>list(state, PLANNED_WORK)) {
            if (queryCount >= request.maxQueries()) {
                break;
            }
            QueryExecution execution = queryService.queryDimension(request, work.dimensionId(), work.pathFilters(), work.depth());
            pending.add(new WorkExecution(work, execution));
            traces.add(execution.trace());
            queryCount++;
        }
        if (pending.isEmpty()) {
            return stop(state, "executeQueries", "执行 SmartBI 查询", "NO_QUERY_BUDGET", "分支计划没有可执行查询");
        }
        String detail = "本轮执行 " + pending.size() + " 个有限分支查询";
        emit(state, "executeQueries", "执行 SmartBI 查询", "COMPLETED", detail, null);
        return Map.of(
                PENDING, List.copyOf(pending),
                QUERY_COUNT, queryCount,
                TRACES, List.copyOf(traces),
                STEPS, appendStep(state, step("executeQueries", "执行 SmartBI 查询", detail)));
    }

    private Map<String, Object> computeEvidence(AttributionState state) {
        emit(state, "computeEvidence", "计算归因证据", "RUNNING", "正在由 Java 计算变化额、贡献度和一致性", null);
        EffectiveRequest request = required(state, REQUEST);
        List<Evidence> allEvidence = new ArrayList<>(list(state, EVIDENCE));
        List<Evidence> currentEvidence = new ArrayList<>();
        List<AnalysisBranch> branches = new ArrayList<>(list(state, BRANCHES));
        for (WorkExecution pending : this.<WorkExecution>list(state, PENDING)) {
            WorkItem work = pending.work();
            Evidence raw = calculator.evidence(
                    request, work.scopeOverall(), work.hypothesis(), work.dimensionId(), work.depth(),
                    work.pathFilters(), pending.execution().response());
            Evidence evidence = new Evidence(
                    raw.id(), work.branchId(), raw.depth(), raw.hypothesis(), raw.dimensionId(), raw.dimensionName(),
                    raw.pathFilters(), raw.members(), raw.primaryDriver(), raw.topNCoverageRate(), raw.dataConsistent());
            allEvidence.add(evidence);
            currentEvidence.add(evidence);
            if (!"root".equals(work.branchId())) {
                branches = changeBranch(branches, work.branchId(), "ANALYZED", null, 1);
            }
        }
        String detail = "Java 已完成变化额、贡献度、排序和一致性校验";
        emit(state, "computeEvidence", "计算归因证据", "COMPLETED", detail, null);
        return Map.of(
                EVIDENCE, List.copyOf(allEvidence),
                CURRENT_EVIDENCE, List.copyOf(currentEvidence),
                BRANCHES, List.copyOf(branches),
                STEPS, appendStep(state, step("computeEvidence", "计算归因证据", detail)));
    }

    private Map<String, Object> reasonEvidence(AttributionState state) {
        emit(state, "reasonEvidence", "反思分支", "RUNNING", "正在请求 LLM 审视本轮 Evidence 并提出有限分支动作", null);
        EffectiveRequest request = required(state, REQUEST);
        List<Evidence> current = list(state, CURRENT_EVIDENCE);
        if (number(state, QUERY_COUNT) >= request.maxQueries()) {
            return stop(state, "reasonEvidence", "反思分支", "MAX_QUERIES", "已达到最大查询次数 " + request.maxQueries());
        }
        List<Evidence> eligible = current.stream().filter(this::hasInformationGain).toList();
        if (eligible.isEmpty()) {
            List<AnalysisBranch> stopped = markCurrentBranchesStopped(list(state, BRANCHES), current, "LOW_INFORMATION_GAIN");
            String detail = "当前分支信息增益不足，停止扩展";
            emit(state, "reasonEvidence", "反思分支", "COMPLETED", detail, null);
            return Map.of(
                    BRANCHES, stopped,
                    STOP, new StopInfo("LOW_SIGNAL", "当前分支没有达到信息增益阈值的同向驱动成员"),
                    ROUTE, "stop",
                    STEPS, appendStep(state, step("reasonEvidence", "反思分支", detail)));
        }
        if (eligible.stream().allMatch(item -> item.depth() >= request.maxDepth())) {
            return stop(state, "reasonEvidence", "反思分支", "MAX_DEPTH", "已达到最大下钻深度 " + request.maxDepth());
        }
        List<AttributionDimension> candidates = AttributionCatalog.dimensions();
        ReflectionDecision decision = reasoner.reflect(
                request, required(state, OVERALL), eligible, list(state, BRANCHES), candidates,
                request.maxQueries() - number(state, QUERY_COUNT), request.maxBranches());
        ReasoningStep reasoning = new ReasoningStep(
                maxDepth(eligible), "REFLECT", decision.reflection(), List.of(), null, null, null,
                "LLM 提议有限分支动作，Java 将执行合法性、预算与去重校验", decision.actions(), decision.llmMessage());
        String detail = "收到 " + decision.actions().size() + " 个候选分支动作";
        emit(state, "reasonEvidence", "反思分支", "COMPLETED", detail, reasoning);
        return Map.of(
                DECISION, decision,
                ROUTE, "continue",
                REASONING, appendReasoning(state, reasoning),
                STEPS, appendStep(state, step("reasonEvidence", "反思分支", detail)));
    }

    private Map<String, Object> prepareNext(AttributionState state) {
        emit(state, "prepareNext", "Java 有限分支调度", "RUNNING", "正在校验候选动作的预算、路径、白名单和去重", null);
        EffectiveRequest request = required(state, REQUEST);
        ReflectionDecision decision = required(state, DECISION);
        List<Evidence> current = list(state, CURRENT_EVIDENCE);
        Map<String, Evidence> byId = current.stream().collect(java.util.stream.Collectors.toMap(
                Evidence::id, item -> item, (left, right) -> left, LinkedHashMap::new));
        Set<String> visited = new LinkedHashSet<>(this.<String>set(state, VISITED));
        List<AnalysisBranch> branches = new ArrayList<>(list(state, BRANCHES));
        List<WorkItem> nextWork = new ArrayList<>();
        List<String> rejections = new ArrayList<>();
        int capacity = request.maxBranches();
        int approvedActions = 0;

        List<BranchAction> actions = decision.actions().stream()
                .sorted(Comparator.comparingInt((BranchAction action) -> priority(action.priority()))
                        .thenComparing(BranchAction::selectedEvidenceId, Comparator.nullsLast(String::compareTo)))
                .toList();
        for (BranchAction action : actions) {
            if (approvedActions >= capacity) {
                break;
            }
            if ("EXPAND".equals(action.action())
                    && number(state, QUERY_COUNT) >= request.maxQueries() - policy.reservedQueries()) {
                rejections.add(shortAction(action));
                continue;
            }
            Optional<ScheduledBranch> scheduled = schedule(
                    action, byId, request, visited, branches, branches.size() + nextWork.size() + 1);
            if (scheduled.isEmpty()) {
                rejections.add(shortAction(action));
                continue;
            }
            ScheduledBranch branch = scheduled.get();
            if ("EXPAND".equals(action.action())) {
                nextWork.add(branch.work());
                visited.add(branch.work().signature());
                branches = changeBranch(branches, branch.parentBranchId(), "EXPANDED", null, 0);
                branches.add(branch.branch());
                approvedActions++;
            } else if ("HOLD".equals(action.action()) || "STOP".equals(action.action())) {
                branches.add(branch.branch());
                approvedActions++;
            }
        }
        String detail = nextWork.isEmpty()
                ? "没有通过 Java 调度校验的可扩展分支"
                : "批准 " + nextWork.size() + " 个分支" + (rejections.isEmpty() ? "" : "；拒绝 " + rejections.size() + " 个无效/重复动作");
        if (nextWork.isEmpty()) {
            emit(state, "prepareNext", "Java 有限分支调度", "COMPLETED", detail, null);
            return Map.of(
                    BRANCHES, List.copyOf(branches),
                    VISITED, Set.copyOf(visited),
                    STOP, new StopInfo("NO_APPROVED_BRANCH", detail),
                    ROUTE, "stop",
                    STEPS, appendStep(state, step("prepareNext", "Java 有限分支调度", detail)));
        }
        emit(state, "prepareNext", "Java 有限分支调度", "COMPLETED", detail, null);
        return Map.of(
                PLANNED_WORK, List.copyOf(nextWork),
                BRANCHES, List.copyOf(branches),
                VISITED, Set.copyOf(visited),
                ROUTE, "continue",
                STEPS, appendStep(state, step("prepareNext", "Java 有限分支调度", detail)));
    }

    private Optional<ScheduledBranch> schedule(
            BranchAction action,
            Map<String, Evidence> evidenceById,
            EffectiveRequest request,
            Set<String> visited,
            List<AnalysisBranch> existingBranches,
            int sequence) {
        if (!("EXPAND".equals(action.action()) || "HOLD".equals(action.action()) || "STOP".equals(action.action()))
                || !("MAIN".equals(action.role()) || "SECONDARY".equals(action.role())
                        || "OFFSET".equals(action.role()) || "UNRESOLVED".equals(action.role()))) {
            return Optional.empty();
        }
        Evidence evidence = evidenceById.get(action.selectedEvidenceId());
        if (evidence == null || evidence.depth() >= request.maxDepth()) {
            return Optional.empty();
        }
        MemberEvidence member = evidence.members().stream()
                .filter(item -> item.memberValue().equals(action.selectedMember()))
                .filter(MemberEvidence::alignedWithOverall)
                .findFirst().orElse(null);
        if (member == null) {
            return Optional.empty();
        }
        List<DimensionFilter> pathFilters = new ArrayList<>(evidence.pathFilters());
        pathFilters.add(new DimensionFilter(evidence.dimensionId(), "EQUALS", List.of(member.memberValue())));
        List<PathNode> path = new ArrayList<>();
        existingBranches.stream().filter(branch -> branch.id().equals(evidence.branchId())).findFirst()
                .ifPresent(branch -> path.addAll(branch.path()));
        path.add(new PathNode(evidence.depth(), evidence.dimensionId(), evidence.dimensionName(), member.memberValue(),
                member.changeAmount(), member.contributionRate()));
        String branchId = "branch-" + sequence;
        if (!"EXPAND".equals(action.action())) {
            return Optional.of(new ScheduledBranch(null, new AnalysisBranch(
                    branchId, evidence.branchId(), action.role(), "HOLD".equals(action.action()) ? "HELD" : "STOPPED",
                    evidence.depth(), pathFilters, path, action.hypothesis(), action.reason(), 0), evidence.branchId()));
        }
        List<AttributionDimension> allowed = remainingDimensions(request, pathFilters);
        if (allowed.stream().noneMatch(dimension -> dimension.id().equals(action.nextDimension()))) {
            return Optional.empty();
        }
        WorkItem work = new WorkItem(branchId, evidence.branchId(), evidence.depth() + 1, pathFilters, path,
                new OverallEvidence(member.currentValue(), member.comparisonValue(), member.changeAmount(), member.changeRate(), null,
                        member.direction()), action.hypothesis(), action.nextDimension(), action.role());
        if (visited.contains(work.signature())) {
            return Optional.empty();
        }
        AnalysisBranch branch = new AnalysisBranch(branchId, evidence.branchId(), action.role(), "QUEUED", work.depth(),
                pathFilters, path, action.hypothesis(), null, 0);
        return Optional.of(new ScheduledBranch(work, branch, evidence.branchId()));
    }

    private Map<String, Object> generateReport(AttributionState state) {
        emit(state, "generateReport", "生成归因报告", "RUNNING", "正在请求 LLM 基于已验证 Evidence 组织报告", null);
        EffectiveRequest request = required(state, REQUEST);
        StopInfo stop = state.<StopInfo>value(STOP).orElseGet(() -> new StopInfo("COMPLETED", "归因分析完成"));
        List<AnalysisBranch> branches = list(state, BRANCHES);
        List<PathNode> path = primaryPath(branches, list(state, EVIDENCE));
        ReportDecision report = reasoner.report(request, required(state, OVERALL), list(state, EVIDENCE), path, branches, stop);
        List<ReasoningStep> reasoning = new ArrayList<>(list(state, REASONING));
        reasoning.add(new ReasoningStep(0, "REPORT", report.report().summary(), List.of(), null, null, null,
                "根据已验证 Evidence 组织报告", List.of(), report.llmMessage()));
        List<WorkflowStep> steps = appendStep(state, step("generateReport", "生成归因报告", stop.detail()));
        AttributionResponse response = new AttributionResponse(
                "completed", request.metricId(), AttributionCatalog.metricName(request.metricId()), request.currentPeriod(),
                request.comparisonPeriod(), required(state, OVERALL), list(state, EVIDENCE), path, branches,
                List.copyOf(reasoning), stop, report.report(), number(state, QUERY_COUNT),
                "LangGraph4j → " + reasoner.engineLabel(request.model()) + " → SmartBI Client", steps, list(state, TRACES));
        emit(state, "generateReport", "生成归因报告", "COMPLETED", stop.detail(), reasoning.get(reasoning.size() - 1));
        return Map.of(RESULT, response, REASONING, List.copyOf(reasoning), STEPS, steps);
    }

    private boolean hasInformationGain(Evidence evidence) {
        return evidence.members().size() >= policy.minimumDistinctMembers()
                && evidence.primaryDriver() != null
                && evidence.primaryDriver().alignedWithOverall()
                && evidence.primaryDriver().contributionRate().abs().compareTo(policy.minAlignedContributionRate()) >= 0;
    }

    private List<AnalysisBranch> markCurrentBranchesStopped(List<AnalysisBranch> branches, List<Evidence> evidence, String reason) {
        Set<String> branchIds = evidence.stream().map(Evidence::branchId).filter(id -> !"root".equals(id)).collect(java.util.stream.Collectors.toSet());
        List<AnalysisBranch> result = new ArrayList<>(branches);
        for (String id : branchIds) {
            result = changeBranch(result, id, "STOPPED", reason, 0);
        }
        return List.copyOf(result);
    }

    private List<AnalysisBranch> changeBranch(
            List<AnalysisBranch> branches, String id, String status, String stopReason, int queryIncrement) {
        if (id == null || "root".equals(id)) {
            return branches;
        }
        return new ArrayList<>(branches.stream().map(branch -> branch.id().equals(id)
                ? new AnalysisBranch(branch.id(), branch.parentBranchId(), branch.role(), status, branch.depth(),
                        branch.pathFilters(), branch.path(), branch.hypothesis(), stopReason == null ? branch.stopReason() : stopReason,
                        branch.queryCount() + queryIncrement)
                : branch).toList());
    }

    private List<PathNode> primaryPath(List<AnalysisBranch> branches, List<Evidence> evidence) {
        AnalysisBranch primary = branches.stream().filter(branch -> "MAIN".equals(branch.role()))
                .max(Comparator.comparingInt((AnalysisBranch branch) -> branch.path().size()).thenComparing(AnalysisBranch::id))
                .orElse(null);
        List<PathNode> path = new ArrayList<>(primary == null ? List.of() : primary.path());
        if (primary != null) {
            evidence.stream().filter(item -> item.branchId().equals(primary.id())).filter(this::hasInformationGain)
                    .max(Comparator.comparingInt(Evidence::depth)).ifPresent(item -> path.add(new PathNode(item.depth(),
                            item.dimensionId(), item.dimensionName(), item.primaryDriver().memberValue(),
                            item.primaryDriver().changeAmount(), item.primaryDriver().contributionRate())));
        }
        if (path.isEmpty()) {
            evidence.stream().filter(this::hasInformationGain).max(Comparator.comparingInt(Evidence::depth)).ifPresent(item ->
                    path.add(new PathNode(item.depth(), item.dimensionId(), item.dimensionName(), item.primaryDriver().memberValue(),
                            item.primaryDriver().changeAmount(), item.primaryDriver().contributionRate())));
        }
        return List.copyOf(path);
    }

    private List<AttributionDimension> remainingDimensions(EffectiveRequest request, List<DimensionFilter> pathFilters) {
        Set<String> excluded = new LinkedHashSet<>();
        request.dimensionFilters().forEach(filter -> excluded.add(filter.dimensionId()));
        pathFilters.forEach(filter -> excluded.add(filter.dimensionId()));
        return AttributionCatalog.dimensions().stream().filter(dimension -> !excluded.contains(dimension.id())).toList();
    }

    private void validateDimensions(List<String> requested, List<AttributionDimension> candidates) {
        Set<String> allowed = candidates.stream().map(AttributionDimension::id).collect(java.util.stream.Collectors.toSet());
        if (requested == null || requested.isEmpty() || requested.size() != new LinkedHashSet<>(requested).size()
                || requested.stream().anyMatch(id -> !allowed.contains(id))) {
            throw new IllegalStateException("LLM 返回了非法或重复的归因维度");
        }
    }

    private Map<String, Object> stop(AttributionState state, String node, String name, String code, String detail) {
        emit(state, node, name, "COMPLETED", detail, null);
        return Map.of(STOP, new StopInfo(code, detail), ROUTE, "stop", STEPS, appendStep(state, step(node, name, detail)));
    }

    private void emit(
            AttributionState state, String node, String name, String status, String detail, ReasoningStep reasoningStep) {
        String observerId = state.<String>value(OBSERVER_ID).orElse("");
        WorkflowObserver observer = OBSERVERS.get(observerId);
        if (observer != null) {
            observer.accept(new WorkflowEvent(node, name, status, detail, reasoningStep));
        }
    }

    private int maxDepth(List<Evidence> evidence) {
        return evidence.stream().mapToInt(Evidence::depth).max().orElse(0);
    }

    private int priority(String value) {
        return "HIGH".equals(value) ? 0 : "MEDIUM".equals(value) ? 1 : "LOW".equals(value) ? 2 : 3;
    }

    private String shortAction(BranchAction action) {
        return (action.action() == null ? "UNKNOWN" : action.action()) + ":" + (action.selectedEvidenceId() == null ? "?" : action.selectedEvidenceId());
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
        List<ReasoningStep> values = new ArrayList<>(list(state, REASONING));
        values.add(reasoning);
        return List.copyOf(values);
    }

    private int number(AttributionState state, String key) {
        return state.<Integer>value(key).orElse(0);
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> list(AttributionState state, String key) {
        return state.<List<T>>value(key).orElseGet(List::of);
    }

    @SuppressWarnings("unchecked")
    private <T> Set<T> set(AttributionState state, String key) {
        return state.<Set<T>>value(key).orElseGet(Set::of);
    }

    private <T> T required(AttributionState state, String key) {
        return state.<T>value(key).orElseThrow(() -> new IllegalStateException("归因 State 缺少 " + key));
    }

    private record WorkItem(
            String branchId, String parentBranchId, int depth, List<DimensionFilter> pathFilters, List<PathNode> path,
            OverallEvidence scopeOverall, String hypothesis, String dimensionId, String role) implements Serializable {
        private WorkItem {
            pathFilters = List.copyOf(pathFilters);
            path = List.copyOf(path);
        }

        static WorkItem root(String dimensionId, String hypothesis, OverallEvidence overall) {
            return new WorkItem("root", null, 1, List.of(), List.of(), overall, hypothesis, dimensionId, "MAIN");
        }

        String signature() {
            return pathFilters + "|" + dimensionId;
        }
    }

    private record WorkExecution(WorkItem work, QueryExecution execution) implements Serializable {
    }

    private record ScheduledBranch(WorkItem work, AnalysisBranch branch, String parentBranchId) {
    }

    @FunctionalInterface
    public interface WorkflowObserver extends Consumer<WorkflowEvent> {
    }

    public record WorkflowEvent(
            String node, String name, String status, String detail, ReasoningStep reasoningStep) implements Serializable {
    }

    static final class AttributionState extends AgentState {
        static final Map<String, Channel<?>> SCHEMA = Map.ofEntries(
                Map.entry(REQUEST, Channels.base((Supplier<Object>) Map::of)),
                Map.entry(OVERALL, Channels.base((Supplier<Object>) Map::of)),
                Map.entry(QUERY_COUNT, Channels.base(() -> 0)),
                Map.entry(PLANNED_WORK, Channels.base((Supplier<List<WorkItem>>) List::of)),
                Map.entry(PENDING, Channels.base((Supplier<List<WorkExecution>>) List::of)),
                Map.entry(CURRENT_EVIDENCE, Channels.base((Supplier<List<Evidence>>) List::of)),
                Map.entry(EVIDENCE, Channels.base((Supplier<List<Evidence>>) List::of)),
                Map.entry(BRANCHES, Channels.base((Supplier<List<AnalysisBranch>>) List::of)),
                Map.entry(VISITED, Channels.base((Supplier<Set<String>>) Set::of)),
                Map.entry(REASONING, Channels.base((Supplier<List<ReasoningStep>>) List::of)),
                Map.entry(TRACES, Channels.base((Supplier<List<QueryTrace>>) List::of)),
                Map.entry(STEPS, Channels.base((Supplier<List<WorkflowStep>>) List::of)),
                Map.entry(DECISION, Channels.base((Supplier<Object>) Map::of)),
                Map.entry(STOP, Channels.base((Supplier<Object>) Map::of)),
                Map.entry(ROUTE, Channels.base(() -> "stop")),
                Map.entry(OBSERVER_ID, Channels.base(() -> "")),
                Map.entry(RESULT, Channels.base((Supplier<Object>) Map::of)));

        AttributionState(Map<String, Object> initData) {
            super(initData);
        }
    }
}
