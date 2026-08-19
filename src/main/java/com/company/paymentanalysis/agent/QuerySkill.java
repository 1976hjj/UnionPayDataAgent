package com.company.paymentanalysis.agent;

import com.company.paymentanalysis.chat.ChatConversationMemoryService;
import com.company.paymentanalysis.chat.ChatConversationMemoryService.ChatMemoryUnavailableException;
import com.company.paymentanalysis.chat.ConversationRouterService;
import com.company.paymentanalysis.controller.ChatQueryController.ChatRequest;
import com.company.paymentanalysis.controller.ChatQueryController.ChatResponse;
import com.company.paymentanalysis.controller.ChatQueryController.QueryContext;
import org.springframework.stereotype.Service;

/** Adapts the existing BI conversation and query workflow without changing it. */
@Service
public class QuerySkill implements AgentSkill {

    private final ConversationRouterService conversationRouter;
    private final ChatConversationMemoryService memoryService;

    public QuerySkill(
            ConversationRouterService conversationRouter,
            ChatConversationMemoryService memoryService) {
        this.conversationRouter = conversationRouter;
        this.memoryService = memoryService;
    }

    @Override
    public AgentEntryMode entryMode() {
        return AgentEntryMode.BI_CHAT;
    }

    @Override
    public AgentResponse execute(AgentRequest request, AgentContext context) {
        QueryContext restoredContext = context.queryContext();
        try {
            restoredContext = memoryService.restoreContext(context.userId(), context.conversationId())
                    .orElse(context.queryContext());
        } catch (ChatMemoryUnavailableException ignored) {
            // The existing controller has the same graceful degradation behavior.
        }
        ChatResponse response = conversationRouter.respond(new ChatRequest(
                context.userId(), context.conversationId(), request.message(), restoredContext,
                context.model(), context.confirmed() || context.action() == AgentAction.CONFIRM));
        try {
            memoryService.saveTurn(context.userId(), context.conversationId(), request.message(), response);
        } catch (ChatMemoryUnavailableException ignored) {
            // The response is still valid for the active browser session.
        }
        return new AgentResponse(
                response.status(), isQueryResponse(response) ? "QUERY" : "CHAT", response.reply(),
                context.conversationId(), new AgentViewModel("query", response));
    }

    private boolean isQueryResponse(ChatResponse response) {
        return response.queryAction() != null || response.queryPlan() != null || response.result() != null
                || "confirming".equals(response.status()) || "clarifying".equals(response.status());
    }
}
