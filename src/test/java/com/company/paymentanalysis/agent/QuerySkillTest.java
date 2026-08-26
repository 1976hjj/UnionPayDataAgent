package com.company.paymentanalysis.agent;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.company.paymentanalysis.chat.ChatConversationMemoryService;
import com.company.paymentanalysis.chat.ConversationRouterService;
import com.company.paymentanalysis.controller.ChatQueryController.ChatRequest;
import com.company.paymentanalysis.controller.ChatQueryController.ChatResponse;
import com.company.paymentanalysis.controller.ChatQueryController.QueryContext;
import com.company.paymentanalysis.artifact.model.ArtifactType;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class QuerySkillTest {

    @Test
    void activeQueryContinuesWithoutRunningTheInnerChatRouter() {
        ConversationRouterService router = mock(ConversationRouterService.class);
        ChatConversationMemoryService memory = mock(ChatConversationMemoryService.class);
        QueryContext saved = new QueryContext(List.of("trans_cnt_m"), List.of(), List.of(), List.of());
        when(memory.activeSkill("user", "conversation"))
                .thenReturn(Optional.of(ChatConversationMemoryService.ActiveSkill.QUERY));
        when(memory.snapshot("user", "conversation")).thenReturn(Optional.of(
                new ChatConversationMemoryService.ConversationSnapshot(saved, List.of(), List.of(), null, null)));
        when(router.continueQuery(any())).thenReturn(new ChatResponse(
                "confirming", "请确认", List.of(), saved, null, "", List.of(), null,
                "conversation", null, "", null));

        QuerySkill skill = new QuerySkill(router, memory);
        skill.execute(new AgentRequest(
                        "user", "conversation", "dp作为收单机构名称", AgentEntryMode.BI_CHAT, "model", false,
                        null, null, null, null, AgentAction.MESSAGE),
                new AgentContext(
                        "user", "conversation", AgentEntryMode.BI_CHAT, "model", false,
                        null, null, null, AgentAction.MESSAGE));

        verify(router).continueQuery(any());
        verify(router, never()).respond(any());
    }

    @Test
    void colloquialYesConfirmsTheQueryOwnedByTheSkill() {
        ConversationRouterService router = mock(ConversationRouterService.class);
        ChatConversationMemoryService memory = mock(ChatConversationMemoryService.class);
        QueryContext saved = new QueryContext(List.of("trans_cnt_m"), List.of(), List.of(), List.of());
        when(memory.activeSkill("user", "conversation"))
                .thenReturn(Optional.of(ChatConversationMemoryService.ActiveSkill.QUERY));
        when(memory.snapshot("user", "conversation")).thenReturn(Optional.of(
                new ChatConversationMemoryService.ConversationSnapshot(saved, List.of(), List.of(), null, null)));
        when(router.continueQuery(any())).thenReturn(new ChatResponse(
                "completed", "已完成", List.of(), saved, null, "", List.of(), null,
                "conversation", null, "", null));
        QuerySkill skill = new QuerySkill(router, memory);

        skill.execute(new AgentRequest(
                        "user", "conversation", "对", AgentEntryMode.BI_CHAT, "model", false,
                        null, null, null, null, AgentAction.MESSAGE),
                new AgentContext(
                        "user", "conversation", AgentEntryMode.BI_CHAT, "model", false,
                        null, null, null, AgentAction.MESSAGE));

        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(router).continueQuery(request.capture());
        org.assertj.core.api.Assertions.assertThat(request.getValue().confirmed()).isTrue();
    }

    @Test
    void agentConfirmationReusesTheServerSideQueryContext() {
        ConversationRouterService router = mock(ConversationRouterService.class);
        ChatConversationMemoryService memory = mock(ChatConversationMemoryService.class);
        QueryContext saved = new QueryContext(List.of("trans_cnt_m"), List.of(), List.of(), List.of());
        when(memory.restoreContext("user", "conversation")).thenReturn(Optional.of(saved));
        when(router.respond(any())).thenReturn(new ChatResponse(
                "completed", "已完成", List.of(), saved, null, "", List.of(), null,
                "conversation", null, "", null, List.of(), null, "art_query_001"));

        QuerySkill skill = new QuerySkill(router, memory);
        AgentResponse response = skill.execute(new AgentRequest(
                        "user", "conversation", "确认", AgentEntryMode.BI_CHAT, "model", false,
                        null, null, null, null, AgentAction.CONFIRM),
                new AgentContext(
                        "user", "conversation", AgentEntryMode.BI_CHAT, "model", false,
                        null, null, null, AgentAction.CONFIRM));

        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(router).respond(request.capture());
        org.assertj.core.api.Assertions.assertThat(request.getValue().confirmed()).isTrue();
        org.assertj.core.api.Assertions.assertThat(request.getValue().context()).isEqualTo(saved);
        org.assertj.core.api.Assertions.assertThat(skill.descriptor().skillId()).isEqualTo("query");
        org.assertj.core.api.Assertions.assertThat(skill.descriptor().producedArtifactTypes())
                .containsExactly(ArtifactType.QUERY_RESULT);
        org.assertj.core.api.Assertions.assertThat(response.skillId()).isEqualTo("query");
        org.assertj.core.api.Assertions.assertThat(response.outputArtifactIds())
                .containsExactly("art_query_001");
    }
}
