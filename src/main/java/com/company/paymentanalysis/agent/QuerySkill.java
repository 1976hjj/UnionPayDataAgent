package com.company.paymentanalysis.agent;

import com.company.paymentanalysis.chat.ChatConversationMemoryService;
import com.company.paymentanalysis.chat.ChatConversationMemoryService.ChatMemoryUnavailableException;
import com.company.paymentanalysis.chat.ConversationRouterService;
import com.company.paymentanalysis.controller.ChatQueryController.ChatRequest;
import com.company.paymentanalysis.controller.ChatQueryController.ChatResponse;
import com.company.paymentanalysis.controller.ChatQueryController.QueryContext;
import com.company.paymentanalysis.artifact.model.ArtifactType;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Adapts the existing BI conversation and query workflow without changing it. */
@Service
public class QuerySkill implements AgentSkill {

    private static final SkillDescriptor DESCRIPTOR = new SkillDescriptor(
            "query", "对话查数", "将自然语言查询转换为受权限约束的 SmartBI 查询",
            AgentEntryMode.BI_CHAT,
            Set.of(AgentAction.MESSAGE, AgentAction.CONFIRM),
            Set.of(), Set.of(ArtifactType.QUERY_RESULT), true);

    private final ConversationRouterService conversationRouter;
    private final ChatConversationMemoryService memoryService;

    public QuerySkill(
            ConversationRouterService conversationRouter,
            ChatConversationMemoryService memoryService) {
        this.conversationRouter = conversationRouter;
        this.memoryService = memoryService;
    }

    @Override
    public SkillDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public AgentResponse execute(AgentRequest request, AgentContext context) {
        QueryContext restoredContext = context.queryContext();
        String pendingQueryIntent = null;
        try {
            var snapshot = memoryService.snapshot(context.userId(), context.conversationId());
            if (snapshot.isPresent()) {
                restoredContext = snapshot.get().context();
                pendingQueryIntent = snapshot.get().pendingQueryIntent();
            } else {
                restoredContext = memoryService.restoreContext(context.userId(), context.conversationId())
                        .orElse(context.queryContext());
            }
        } catch (ChatMemoryUnavailableException ignored) {
            // The existing controller has the same graceful degradation behavior.
        }
        ChatResponse response = conversationRouter.respond(new ChatRequest(
                context.userId(), context.conversationId(), request.message(), restoredContext,
                context.model(), context.confirmed() || context.action() == AgentAction.CONFIRM,
                pendingQueryIntent));
        try {
            memoryService.saveTurn(context.userId(), context.conversationId(), request.message(), response);
        } catch (ChatMemoryUnavailableException ignored) {
            // The response is still valid for the active browser session.
        }
        List<String> outputArtifactIds = response.artifactId() == null || response.artifactId().isBlank()
                ? List.of() : List.of(response.artifactId());
        return new AgentResponse(
                response.status(), isQueryResponse(response) ? "QUERY" : "CHAT", response.reply(),
                context.conversationId(), new AgentViewModel("query", response),
                DESCRIPTOR.skillId(), outputArtifactIds);
    }

    private boolean isQueryResponse(ChatResponse response) {
        return response.queryAction() != null || response.queryPlan() != null || response.result() != null
                || "confirming".equals(response.status()) || "clarifying".equals(response.status());
    }
}
