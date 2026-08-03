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
                redis, new ObjectMapper(), new ChatMemoryProperties(true, "test:chat:", 30, 50));
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
}
