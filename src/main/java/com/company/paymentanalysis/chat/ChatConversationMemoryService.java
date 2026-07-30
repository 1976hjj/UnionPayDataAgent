package com.company.paymentanalysis.chat;

import com.company.paymentanalysis.controller.ChatQueryController.ChatResponse;
import com.company.paymentanalysis.controller.ChatQueryController.ConversationDetail;
import com.company.paymentanalysis.controller.ChatQueryController.ConversationMessage;
import com.company.paymentanalysis.controller.ChatQueryController.ConversationSummary;
import com.company.paymentanalysis.controller.ChatQueryController.MemoryStatus;
import com.company.paymentanalysis.controller.ChatQueryController.QueryContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class ChatConversationMemoryService {

    private static final Duration REDIS_RETRY_DELAY = Duration.ofSeconds(30);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ChatMemoryProperties properties;
    private volatile Instant redisRetryAfter = Instant.EPOCH;
    private volatile boolean redisAvailable;
    private volatile String redisDetail = "等待首次检查";

    public ChatConversationMemoryService(
            StringRedisTemplate redisTemplate, ObjectMapper objectMapper, ChatMemoryProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public Optional<QueryContext> restoreContext(String userId, String conversationId) {
        return find(userId, conversationId).map(StoredConversation::context);
    }

    public void saveTurn(String userId, String conversationId, String userMessage, ChatResponse response) {
        Instant now = Instant.now();
        StoredConversation previous = find(userId, conversationId).orElse(null);
        List<ConversationMessage> messages =
                new ArrayList<>(previous == null ? List.of() : previous.messages());
        int nextId = messages.stream().mapToInt(ConversationMessage::id).max().orElse(0) + 1;
        messages.add(new ConversationMessage(
                nextId, "user", userMessage, List.of(), null, null, List.of(), null, "normal", null));
        messages.add(new ConversationMessage(
                nextId + 1, "assistant", response.reply(), response.suggestions(), response.result(),
                response.executionEngine(), response.workflowSteps(), response.queryPlan(),
                "rejected".equals(response.status()) ? "rejected" : "normal", response.llmMessage()));

        StoredConversation saved = new StoredConversation(
                userId, conversationId, previous == null ? title(userMessage) : previous.title(),
                previous == null ? now.toString() : previous.createdAt(),
                now.toString(), response.context(), List.copyOf(messages));
        save(saved);
    }

    public List<ConversationSummary> list(String userId) {
        List<StoredConversation> conversations = listStored(userId);
        return conversations.stream()
                .sorted(Comparator.comparing(StoredConversation::updatedAt).reversed())
                .limit(maxConversations())
                .map(value -> new ConversationSummary(
                        value.conversationId(), value.title(), value.updatedAt(), value.messages().size()))
                .toList();
    }

    public Optional<ConversationDetail> detail(String userId, String conversationId) {
        return find(userId, conversationId)
                .map(value -> new ConversationDetail(
                        value.conversationId(), value.title(), value.createdAt(), value.updatedAt(),
                        value.context(), value.messages()));
    }

    public MemoryStatus status() {
        checkRedisWhenDue();
        return new MemoryStatus(
                "Redis", properties.redisEnabled(), redisAvailable, redisDetail,
                Math.max(1, properties.ttlDays()));
    }

    private Optional<StoredConversation> find(String userId, String conversationId) {
        ensureRedisAttemptAllowed();
        try {
            String json = redisTemplate.opsForValue().get(conversationKey(userId, conversationId));
            markRedisAvailable();
            return json == null ? Optional.empty() : Optional.of(objectMapper.readValue(json, StoredConversation.class));
        } catch (RuntimeException | JsonProcessingException exception) {
            throw unavailable(exception);
        }
    }

    private void save(StoredConversation conversation) {
        ensureRedisAttemptAllowed();
        try {
            Duration ttl = Duration.ofDays(Math.max(1, properties.ttlDays()));
            String key = conversationKey(conversation.userId(), conversation.conversationId());
            String indexKey = indexKey(conversation.userId());
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(conversation), ttl);
            redisTemplate.opsForZSet().add(
                    indexKey, conversation.conversationId(),
                    Instant.parse(conversation.updatedAt()).toEpochMilli());
            redisTemplate.expire(indexKey, ttl);
            trimIndex(indexKey);
            markRedisAvailable();
        } catch (RuntimeException | JsonProcessingException exception) {
            throw unavailable(exception);
        }
    }

    private List<StoredConversation> listStored(String userId) {
        ensureRedisAttemptAllowed();
        try {
            Set<String> ids =
                    redisTemplate.opsForZSet().reverseRange(indexKey(userId), 0, maxConversations() - 1L);
            List<StoredConversation> values = new ArrayList<>();
            if (ids != null) {
                for (String id : ids) {
                    find(userId, id).ifPresent(values::add);
                }
            }
            markRedisAvailable();
            return values;
        } catch (ChatMemoryUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw unavailable(exception);
        }
    }

    private void trimIndex(String indexKey) {
        Long size = redisTemplate.opsForZSet().size(indexKey);
        int max = maxConversations();
        if (size != null && size > max) {
            redisTemplate.opsForZSet().removeRange(indexKey, 0, size - max - 1);
        }
    }

    private void ensureRedisAttemptAllowed() {
        if (!properties.redisEnabled()) {
            redisAvailable = false;
            redisDetail = "Redis 会话记忆未启用";
            throw new ChatMemoryUnavailableException(redisDetail);
        }
        if (Instant.now().isBefore(redisRetryAfter)) {
            throw new ChatMemoryUnavailableException(redisDetail);
        }
    }

    private void markRedisAvailable() {
        redisAvailable = true;
        redisDetail = "连接正常";
        redisRetryAfter = Instant.EPOCH;
    }

    private ChatMemoryUnavailableException unavailable(Exception cause) {
        redisAvailable = false;
        redisDetail = "连接失败，历史会话暂不可用";
        redisRetryAfter = Instant.now().plus(REDIS_RETRY_DELAY);
        return new ChatMemoryUnavailableException(redisDetail, cause);
    }

    private void checkRedisWhenDue() {
        if (!properties.redisEnabled()) {
            redisAvailable = false;
            redisDetail = "Redis 会话记忆未启用";
            return;
        }
        if (Instant.now().isBefore(redisRetryAfter)) {
            return;
        }
        try (RedisConnection connection = redisTemplate.getConnectionFactory().getConnection()) {
            connection.ping();
            markRedisAvailable();
        } catch (RuntimeException exception) {
            unavailable(exception);
        }
    }

    private String title(String message) {
        String normalized = message.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 24 ? normalized : normalized.substring(0, 24) + "…";
    }

    private int maxConversations() {
        return Math.max(1, properties.maxConversations());
    }

    private String prefix() {
        String prefix = properties.keyPrefix();
        return prefix == null || prefix.isBlank() ? "payment-analysis:chat:" : prefix;
    }

    private String conversationKey(String userId, String conversationId) {
        return prefix() + "conversation:" + userId + ":" + conversationId;
    }

    private String indexKey(String userId) {
        return prefix() + "user:" + userId + ":conversations";
    }

    private record StoredConversation(
            String userId, String conversationId, String title, String createdAt, String updatedAt,
            QueryContext context, List<ConversationMessage> messages) {
    }

    public static class ChatMemoryUnavailableException extends RuntimeException {
        public ChatMemoryUnavailableException(String message) {
            super(message);
        }

        public ChatMemoryUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
