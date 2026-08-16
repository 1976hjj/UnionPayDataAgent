package com.company.paymentanalysis.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.paymentanalysis.controller.ChatQueryController.ChatResponse;
import com.company.paymentanalysis.controller.ChatQueryController.QueryContext;
import com.company.paymentanalysis.controller.ChatQueryController.WorkflowStep;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

class ChatConversationMemoryServiceTest {

    @SuppressWarnings("unchecked")
    @Test
    void storesAndRestoresConversationOnlyThroughRedisOperations() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        ZSetOperations<String, String> sortedSets = mock(ZSetOperations.class);
        Map<String, String> redisValues = new LinkedHashMap<>();
        Set<String> conversationIds = new LinkedHashSet<>();

        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForZSet()).thenReturn(sortedSets);
        when(values.get(anyString()))
                .thenAnswer(invocation -> redisValues.get(invocation.getArgument(0, String.class)));
        doAnswer(invocation -> {
                    redisValues.put(invocation.getArgument(0, String.class), invocation.getArgument(1, String.class));
                    return null;
                })
                .when(values)
                .set(anyString(), anyString(), any(Duration.class));
        when(sortedSets.add(anyString(), anyString(), anyDouble())).thenAnswer(invocation -> {
            conversationIds.add(invocation.getArgument(1, String.class));
            return true;
        });
        when(sortedSets.reverseRange(anyString(), anyLong(), anyLong())).thenReturn(conversationIds);
        when(sortedSets.size(anyString())).thenReturn(1L);
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(true);
        when(redis.delete(anyString())).thenAnswer(invocation ->
                redisValues.remove(invocation.getArgument(0, String.class)) != null);
        when(sortedSets.remove(anyString(), anyString())).thenAnswer(invocation ->
                conversationIds.remove(invocation.getArgument(1, String.class)) ? 1L : 0L);

        ChatConversationMemoryService service = new ChatConversationMemoryService(
                redis, new ObjectMapper(), new ChatMemoryProperties(true, "test:chat:", 30, 50, 100, 20));
        QueryContext context = new QueryContext(
                List.of("transactionAmount"), List.of("channel"), List.of(), List.of());
        ChatResponse response = new ChatResponse(
                "completed", "查询完成", List.of(), context, null, "LangGraph4j → Mock LLM → Mock SmartBI",
                List.of(new WorkflowStep("node", "节点", "COMPLETED", "完成")),
                null, "conversation-1", null, "查询指定条件。", null);

        service.saveTurn("user-1", "conversation-1", "查7月交易金额", response);

        assertThat(service.restoreContext("user-1", "conversation-1")).contains(context);
        assertThat(service.list("user-1"))
                .singleElement()
                .satisfies(summary -> {
                    assertThat(summary.conversationId()).isEqualTo("conversation-1");
                    assertThat(summary.messageCount()).isEqualTo(2);
                });
        assertThat(service.detail("user-1", "conversation-1"))
                .hasValueSatisfying(detail -> {
                    assertThat(detail.messages()).hasSize(2);
                    assertThat(detail.messages().get(1).workflowSteps()).hasSize(1);
                });

        assertThat(service.deleteConversation("user-1", "conversation-1")).isTrue();
        assertThat(service.restoreContext("user-1", "conversation-1")).isEmpty();
        assertThat(service.list("user-1")).isEmpty();
        assertThat(service.deleteConversation("user-1", "conversation-1")).isFalse();
    }

    @Test
    void fallsBackToInProcessMemoryWhenRedisIsDisabled() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ChatConversationMemoryService service = new ChatConversationMemoryService(
                redis, new ObjectMapper(), new ChatMemoryProperties(false, "test:chat:", 30, 50, 100, 20));
        QueryContext context = new QueryContext(
                List.of("transactionAmount"), List.of("channel"), List.of(), List.of());
        ChatResponse response = new ChatResponse(
                "completed", "query completed", List.of(), context, null,
                "LangGraph4j -> LLM -> SmartBI", List.of(), null,
                "conversation-local", null, "temporary memory", null);

        service.saveTurn("user-local", "conversation-local", "recent transactions", response);

        assertThat(service.restoreContext("user-local", "conversation-local")).contains(context);
        assertThat(service.list("user-local"))
                .singleElement()
                .satisfies(summary -> assertThat(summary.conversationId()).isEqualTo("conversation-local"));
        assertThat(service.detail("user-local", "conversation-local")).isPresent();
        assertThat(service.status().available()).isFalse();
        assertThat(service.status().detail()).contains("进程内临时会话");
        assertThat(service.deleteConversation("user-local", "conversation-local")).isTrue();
        assertThat(service.restoreContext("user-local", "conversation-local")).isEmpty();
    }

    @Test
    void keepsOnlyNewestMessagesAndArtifactsWithinConfiguredCapacity() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ChatConversationMemoryService service = new ChatConversationMemoryService(
                redis, new ObjectMapper(), new ChatMemoryProperties(
                        false, "test:chat:", 30, 50, 4, 2));
        QueryContext context = QueryContext.empty();
        ChatResponse response = new ChatResponse(
                "completed", "done", List.of(), context, null, "Conversation Agent",
                List.of(), null, "capacity-test", null, "done", null);

        service.saveTurn("capacity-user", "capacity-test", "turn-1", response);
        service.saveTurn("capacity-user", "capacity-test", "turn-2", response);
        service.saveTurn("capacity-user", "capacity-test", "turn-3", response);
        service.saveAttributionArtifact(
                "capacity-user", "capacity-test", "artifact-1", "summary-1", "request-1", "evidence-1");
        service.saveAttributionArtifact(
                "capacity-user", "capacity-test", "artifact-2", "summary-2", "request-2", "evidence-2");
        service.saveAttributionArtifact(
                "capacity-user", "capacity-test", "artifact-3", "summary-3", "request-3", "evidence-3");

        var snapshot = service.snapshot("capacity-user", "capacity-test").orElseThrow();
        assertThat(snapshot.messages()).hasSize(4);
        assertThat(snapshot.messages()).extracting(message -> message.id())
                .containsExactly(6, 7, 8, 9);
        assertThat(snapshot.messages().get(0).text()).isEqualTo("done");
        assertThat(snapshot.messages().get(1).text()).contains("summary-1");
        assertThat(snapshot.messages().get(2).text()).contains("summary-2");
        assertThat(snapshot.messages().get(3).text()).contains("summary-3");
        assertThat(snapshot.artifacts()).extracting(ConversationArtifact::title)
                .containsExactly("artifact-2", "artifact-3");
    }
}
