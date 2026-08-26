package com.company.paymentanalysis.agent;

import com.company.paymentanalysis.attribution.AttributionTemplateInterpreter;
import com.company.paymentanalysis.attribution.AttributionExecutionService;
import com.company.paymentanalysis.attribution.AttributionModels.AnalysisLevel;
import com.company.paymentanalysis.attribution.AttributionModels.AnalysisPlan;
import com.company.paymentanalysis.attribution.AttributionModels.AttributionRequest;
import com.company.paymentanalysis.attribution.AttributionModels.AttributionResponse;
import com.company.paymentanalysis.attribution.AttributionModels.DimensionFilter;
import com.company.paymentanalysis.attribution.AttributionTemplateModels.DimensionLayer;
import com.company.paymentanalysis.attribution.AttributionTemplateModels.DimensionTemplate;
import com.company.paymentanalysis.attribution.AttributionTemplateModels.TemplateChatRequest;
import com.company.paymentanalysis.attribution.AttributionTemplateModels.TemplateChatResponse;
import com.company.paymentanalysis.attribution.AttributionTemplateModels.TemplateConversationState;
import com.company.paymentanalysis.attribution.AttributionTemplateModels.TemplateFilter;
import com.company.paymentanalysis.artifact.model.ArtifactType;
import com.company.paymentanalysis.chat.AttributionConversationRouterService;
import com.company.paymentanalysis.chat.ChatConversationMemoryService;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Phase-one adapter for the existing attribution-template conversation. */
@Service
public class AttributionSkill implements AgentSkill {

    private static final SkillDescriptor DESCRIPTOR = new SkillDescriptor(
            "attribution", "归因分析", "对已确认的指标、周期和分析路径执行受约束的归因分析",
            AgentEntryMode.ATTRIBUTION,
            Set.of(AgentAction.MESSAGE, AgentAction.CONFIRM, AgentAction.EXECUTE),
            Set.of(), Set.of(ArtifactType.ATTRIBUTION_RESULT), true);

    private final AttributionConversationRouterService conversationRouter;
    private final AttributionTemplateInterpreter templateInterpreter;
    private final ChatConversationMemoryService memoryService;
    private final AttributionExecutionService executionService;

    public AttributionSkill(
            AttributionConversationRouterService conversationRouter,
            AttributionTemplateInterpreter templateInterpreter,
            ChatConversationMemoryService memoryService,
            AttributionExecutionService executionService) {
        this.conversationRouter = conversationRouter;
        this.templateInterpreter = templateInterpreter;
        this.memoryService = memoryService;
        this.executionService = executionService;
    }

    @Override
    public SkillDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public AgentResponse execute(AgentRequest request, AgentContext context) {
        if (context.action() == AgentAction.CONFIRM || naturalConfirmation(request, context)) {
            return confirm(context);
        }
        if (context.action() == AgentAction.EXECUTE || naturalExecution(request, context)) {
            return executeConfirmedTemplate(request, context);
        }
        TemplateChatResponse response = conversationRouter.respond(new TemplateChatRequest(
                context.userId(), context.conversationId(), request.message(), List.of(),
                context.attributionTemplate(), context.model()));
        return new AgentResponse(
                response.status(), "CHAT".equals(response.status()) ? "CHAT" : "ATTRIBUTION",
                response.reply(), context.conversationId(),
                new AgentViewModel("attribution-template", response), DESCRIPTOR.skillId(), List.of());
    }

    private AgentResponse confirm(AgentContext context) {
        TemplateConversationState state = memoryService.snapshot(
                        context.userId(), context.conversationId(),
                        ChatConversationMemoryService.ConversationScope.ATTRIBUTION)
                .map(ChatConversationMemoryService.ConversationSnapshot::attributionState)
                .orElseThrow(() -> new IllegalArgumentException("当前会话没有可确认的归因模板"));
        if (state == null || state.template() == null) {
            throw new IllegalArgumentException("当前会话没有可确认的归因模板");
        }
        DimensionTemplate confirmed = templateInterpreter.confirm(state.template());
        memoryService.saveAttributionState(context.userId(), context.conversationId(),
                new TemplateConversationState("READY_TO_EXECUTE", confirmed,
                        state.unmappedTerms(), state.mappingIssues(), state.warnings()));
        TemplateChatResponse response = new TemplateChatResponse(
                "READY_TO_EXECUTE", "归因模板已确认，可以开始执行归因分析。", confirmed,
                state.unmappedTerms(), state.mappingIssues(), state.warnings(), null, null);
        return new AgentResponse(
                response.status(), "ATTRIBUTION", response.reply(),
                context.conversationId(), new AgentViewModel("attribution-template", response),
                DESCRIPTOR.skillId(), List.of());
    }

    private AgentResponse executeConfirmedTemplate(AgentRequest request, AgentContext context) {
        TemplateConversationState state = attributionState(context);
        DimensionTemplate template = state.template();
        if (!"READY_TO_EXECUTE".equals(state.status()) || !"CONFIRMED".equals(template.status())) {
            throw new IllegalArgumentException("请先确认归因模板，再开始归因分析");
        }
        AttributionExecutionService.ExecutedAttribution execution =
                executionService.execute(toRequest(template, request, context));
        AttributionResponse response = execution.response();
        memoryService.saveAttributionState(context.userId(), context.conversationId(),
                new TemplateConversationState("COMPLETED", template,
                        state.unmappedTerms(), state.mappingIssues(), state.warnings()));
        String reply = response.report() == null || response.report().summary() == null
                ? "归因分析已执行完成。" : response.report().summary();
        List<String> outputArtifactIds = execution.artifactId() == null
                ? List.of() : List.of(execution.artifactId());
        return new AgentResponse(response.status(), "ATTRIBUTION", reply,
                context.conversationId(), new AgentViewModel("attribution-result", response),
                DESCRIPTOR.skillId(), outputArtifactIds);
    }

    private boolean naturalConfirmation(AgentRequest request, AgentContext context) {
        if (!affirmative(request.message())) return false;
        return memoryService.snapshot(
                        context.userId(), context.conversationId(),
                        ChatConversationMemoryService.ConversationScope.ATTRIBUTION)
                .map(ChatConversationMemoryService.ConversationSnapshot::attributionState)
                .map(state -> "READY_TO_CONFIRM".equals(state.status()))
                .orElse(false);
    }

    private boolean naturalExecution(AgentRequest request, AgentContext context) {
        String message = normalized(request.message());
        if (!(message.equals("开始") || message.equals("执行") || message.equals("开始执行")
                || message.equals("开始归因") || message.equals("执行归因"))) return false;
        return memoryService.snapshot(
                        context.userId(), context.conversationId(),
                        ChatConversationMemoryService.ConversationScope.ATTRIBUTION)
                .map(ChatConversationMemoryService.ConversationSnapshot::attributionState)
                .map(state -> "READY_TO_EXECUTE".equals(state.status()))
                .orElse(false);
    }

    private boolean affirmative(String message) {
        String normalized = normalized(message);
        return normalized.equals("对") || normalized.equals("是") || normalized.equals("好的")
                || normalized.equals("可以") || normalized.equals("确认")
                || normalized.equals("继续确认") || normalized.equals("就这样")
                || normalized.equals("没问题") || normalized.equals("确认执行");
    }

    private String normalized(String message) {
        return message == null ? "" : message.replaceAll("[，。！？!?,\\s]", "").trim();
    }

    private TemplateConversationState attributionState(AgentContext context) {
        TemplateConversationState state = memoryService.snapshot(
                        context.userId(), context.conversationId(),
                        ChatConversationMemoryService.ConversationScope.ATTRIBUTION)
                .map(ChatConversationMemoryService.ConversationSnapshot::attributionState)
                .orElseThrow(() -> new IllegalArgumentException("当前会话没有可确认的归因模板"));
        if (state == null || state.template() == null) {
            throw new IllegalArgumentException("当前会话没有可确认的归因模板");
        }
        return state;
    }

    private AttributionRequest toRequest(DimensionTemplate template, AgentRequest request, AgentContext context) {
        List<DimensionFilter> filters = template.filters().stream()
                .map(this::toFilter)
                .toList();
        List<AnalysisLevel> levels = template.levels().stream()
                .map(this::toLevel)
                .toList();
        AnalysisPlan plan = levels.isEmpty() ? null
                : new AnalysisPlan(levels, "AUTO".equals(template.continuationMode()));
        AttributionExecutionOptions options = request.attributionExecutionOptions();
        return new AttributionRequest(
                template.metricId(), template.currentPeriod(), template.comparisonPeriod(), filters, plan,
                options == null ? null : options.maxDepth(),
                options == null ? null : options.maxQueries(),
                options == null ? null : options.topN(),
                options == null ? null : options.maxBranches(),
                context.model(), context.userId(), context.conversationId());
    }

    private DimensionFilter toFilter(TemplateFilter filter) {
        return new DimensionFilter(filter.dimensionId(), filter.operator(), filter.values());
    }

    private AnalysisLevel toLevel(DimensionLayer level) {
        return new AnalysisLevel(level.level(), level.dimensions().stream()
                .map(item -> item.dimensionId()).toList());
    }
}
