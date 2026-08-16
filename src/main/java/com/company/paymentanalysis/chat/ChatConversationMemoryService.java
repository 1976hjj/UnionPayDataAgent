package com.company.paymentanalysis.chat;

import com.company.paymentanalysis.attribution.AttributionTemplateModels.TemplateConversationState;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class ChatConversationMemoryService {

    private static final Duration REDIS_RETRY_DELAY = Duration.ofSeconds(30);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final ChatMemoryProperties properties;
    private final ConcurrentHashMap<String, StoredConversation> localConversations = new ConcurrentHashMap<>();
    private volatile Instant redisRetryAfter = Instant.EPOCH;
    private volatile boolean redisAvailable;
    private volatile String redisDetail = "等待首次检查；不可用时自动使用进程内临时会话";

    public ChatConversationMemoryService(
            StringRedisTemplate redisTemplate, ObjectMapper objectMapper, ChatMemoryProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public Optional<QueryContext> restoreContext(String userId, String conversationId) {
        return findWithFallback(userId, conversationId).map(StoredConversation::context);
    }

    /** Returns the bounded data required to ground a later conversation turn. */
    public Optional<ConversationSnapshot> snapshot(String userId, String conversationId) {
        return findWithFallback(userId, conversationId)
                .map(value -> new ConversationSnapshot(
                        value.context(), value.messages(), value.artifacts(), value.attributionState()));
    }

    public void saveTurn(String userId, String conversationId, String userMessage, ChatResponse response) {
        Instant now = Instant.now();
        StoredConversation previous = findWithFallback(userId, conversationId).orElse(null);
        List<ConversationMessage> messages =
                new ArrayList<>(previous == null ? List.of() : previous.messages());
        int nextId = messages.stream().mapToInt(ConversationMessage::id).max().orElse(0) + 1;
        messages.add(new ConversationMessage(
                nextId, "user", userMessage, List.of(), null, null, List.of(), null,
                null, "normal", null, null, null));
        messages.add(new ConversationMessage(
                nextId + 1, "assistant", response.reply(), response.suggestions(), response.result(),
                response.executionEngine(), response.workflowSteps(), response.queryPlan(),
                response.status(), "rejected".equals(response.status()) ? "rejected" : "normal",
                response.queryAction(), response.queryExplanation(), response.llmMessage(),
                response.derivedFromArtifactIds()));

        StoredConversation saved = new StoredConversation(
                userId, conversationId, previous == null ? title(userMessage) : previous.title(),
                previous == null ? now.toString() : previous.createdAt(),
                now.toString(), response.context(), List.copyOf(messages),
                previous == null ? List.of() : previous.artifacts(),
                previous == null ? null : previous.attributionState());
        store(saved);
    }

    public void saveConversationTurn(
            String userId, String conversationId, String userMessage, String assistantReply,
            QueryContext context) {
        Instant now = Instant.now();
        StoredConversation previous = findWithFallback(userId, conversationId).orElse(null);
        List<ConversationMessage> messages =
                new ArrayList<>(previous == null ? List.of() : previous.messages());
        int nextId = messages.stream().mapToInt(ConversationMessage::id).max().orElse(0) + 1;
        messages.add(new ConversationMessage(
                nextId, "user", userMessage, List.of(), null, null, List.of(), null,
                null, "normal", null, null, null));
        messages.add(new ConversationMessage(
                nextId + 1, "assistant", assistantReply, List.of(), null,
                "Conversation Agent", List.of(), null, "completed", "normal",
                null, "基于当前会话与已保存分析产物生成回复。", null));
        store(new StoredConversation(
                userId, conversationId, previous == null ? title(userMessage) : previous.title(),
                previous == null ? now.toString() : previous.createdAt(), now.toString(),
                context == null ? (previous == null ? QueryContext.empty() : previous.context()) : context,
                List.copyOf(messages), previous == null ? List.of() : previous.artifacts(),
                previous == null ? null : previous.attributionState()));
    }

    /** Appends a turn from the attribution page to the same server-side conversation. */
    public void saveAttributionTurn(
            String userId, String conversationId, String userMessage, String assistantReply,
            TemplateConversationState attributionState) {
        Instant now = Instant.now();
        StoredConversation previous = findWithFallback(userId, conversationId).orElse(null);
        List<ConversationMessage> messages = new ArrayList<>(
                previous == null ? List.of() : previous.messages());
        int nextId = messages.stream().mapToInt(ConversationMessage::id).max().orElse(0) + 1;
        messages.add(new ConversationMessage(
                nextId, "user", userMessage, List.of(), null, null, List.of(), null,
                null, "normal", null, null, null));
        messages.add(new ConversationMessage(
                nextId + 1, "assistant", assistantReply, List.of(), null,
                null, List.of(), null, "completed", "normal",
                null, "归因页对话；未执行归因时不会触发 SmartBI。", null));
        store(new StoredConversation(
                userId, conversationId, previous == null ? title(userMessage) : previous.title(),
                previous == null ? now.toString() : previous.createdAt(), now.toString(),
                previous == null ? QueryContext.empty() : previous.context(), List.copyOf(messages),
                previous == null ? List.of() : previous.artifacts(), attributionState));
    }

    public void saveAttributionState(
            String userId, String conversationId, TemplateConversationState attributionState) {
        StoredConversation previous = findWithFallback(userId, conversationId).orElse(null);
        if (previous == null) return;
        store(new StoredConversation(
                previous.userId(), previous.conversationId(), previous.title(), previous.createdAt(),
                Instant.now().toString(), previous.context(), previous.messages(), previous.artifacts(),
                attributionState));
    }

    public ConversationArtifact saveAttributionArtifact(
            String userId, String conversationId, String title, String summary,
            String requestContract, String evidence) {
        return saveAttributionArtifact(
                userId, conversationId, title, summary, requestContract, evidence,
                Map.of(), List.of(), summary);
    }

    public ConversationArtifact saveAttributionArtifact(
            String userId, String conversationId, String title, String summary,
            String requestContract, String evidence, Map<String, String> attributes,
            List<ConversationArtifact.VerifiedFact> verifiedFacts, String modelNarrative) {
        Instant now = Instant.now();
        StoredConversation previous = findWithFallback(userId, conversationId).orElse(null);
        ConversationArtifact artifact = new ConversationArtifact(
                "attr-" + UUID.randomUUID(), "ATTRIBUTION", title, now.toString(),
                summary, requestContract, evidence, attributes, verifiedFacts, modelNarrative);
        List<ConversationArtifact> artifacts = new ArrayList<>(
                previous == null ? List.of() : previous.artifacts());
        artifacts.add(artifact);
        List<ConversationMessage> messages = new ArrayList<>(
                previous == null ? List.of() : previous.messages());
        int nextId = messages.stream().mapToInt(ConversationMessage::id).max().orElse(0) + 1;
        messages.add(new ConversationMessage(
                nextId, "assistant", "已完成“" + title + "”。\n" + summary,
                List.of("基于这份归因写业务汇报", "解释主要下滑原因"), null,
                "Attribution Agent", List.of(), null, "completed", "normal", null,
                "归因结果已保存为可引用产物，可在对话查数中继续追问。", null));
        store(new StoredConversation(
                userId, conversationId, previous == null ? title(title) : previous.title(),
                previous == null ? now.toString() : previous.createdAt(), now.toString(),
                previous == null ? QueryContext.empty() : previous.context(), List.copyOf(messages),
                List.copyOf(artifacts), previous == null ? null : previous.attributionState()));
        return artifact;
    }

    public List<ConversationSummary> list(String userId) {
        List<StoredConversation> conversations;
        try {
            conversations = listStored(userId);
            conversations.forEach(value ->
                    localConversations.put(localKey(value.userId(), value.conversationId()), value));
        } catch (ChatMemoryUnavailableException ignored) {
            conversations = localStored(userId);
        }
        return conversations.stream()
                .sorted(Comparator.comparing(StoredConversation::updatedAt).reversed())
                .limit(maxConversations())
                .map(value -> new ConversationSummary(
                        value.conversationId(), value.title(), value.updatedAt(), value.messages().size()))
                .toList();
    }

    public Optional<ConversationDetail> detail(String userId, String conversationId) {
        return findWithFallback(userId, conversationId)
                .map(value -> new ConversationDetail(
                        value.conversationId(), value.title(), value.createdAt(), value.updatedAt(),
                        value.context(), value.messages(), value.attributionState()));
    }

    public boolean deleteConversation(String userId, String conversationId) {
        boolean localDeleted = localConversations.remove(localKey(userId, conversationId)) != null;
        try {
            ensureRedisAttemptAllowed();
            String conversationKey = conversationKey(userId, conversationId);
            Boolean deleted = redisTemplate.delete(conversationKey);
            redisTemplate.opsForZSet().remove(indexKey(userId), conversationId);
            markRedisAvailable();
            return localDeleted || Boolean.TRUE.equals(deleted);
        } catch (ChatMemoryUnavailableException ignored) {
            return localDeleted;
        } catch (RuntimeException exception) {
            unavailable(exception);
            return localDeleted;
        }
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
            return json == null
                    ? Optional.empty()
                    : Optional.of(enforceConversationLimits(
                            objectMapper.readValue(json, StoredConversation.class)));
        } catch (RuntimeException | JsonProcessingException exception) {
            throw unavailable(exception);
        }
    }

    private Optional<StoredConversation> findWithFallback(String userId, String conversationId) {
        try {
            Optional<StoredConversation> stored = find(userId, conversationId);
            stored.ifPresent(value -> localConversations.put(localKey(userId, conversationId), value));
            return stored.isPresent()
                    ? stored
                    : Optional.ofNullable(localConversations.get(localKey(userId, conversationId)));
        } catch (ChatMemoryUnavailableException ignored) {
            return Optional.ofNullable(localConversations.get(localKey(userId, conversationId)));
        }
    }

    private List<StoredConversation> localStored(String userId) {
        return localConversations.values().stream()
                .filter(value -> value.userId().equals(userId))
                .toList();
    }

    private String localKey(String userId, String conversationId) {
        return userId + "\u0000" + conversationId;
    }

    private void trimLocal(String userId) {
        localStored(userId).stream()
                .sorted(Comparator.comparing(StoredConversation::updatedAt).reversed())
                .skip(maxConversations())
                .forEach(value -> localConversations.remove(localKey(userId, value.conversationId()), value));
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

    private void store(StoredConversation conversation) {
        conversation = enforceConversationLimits(conversation);
        localConversations.put(localKey(conversation.userId(), conversation.conversationId()), conversation);
        trimLocal(conversation.userId());
        try {
            save(conversation);
        } catch (ChatMemoryUnavailableException ignored) {
            // Redis is optional. Keep the current conversation in this Java process.
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
            redisDetail = "Redis 未启用，当前使用进程内临时会话";
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
        redisDetail = "Redis 连接失败，已切换到进程内临时会话";
        redisRetryAfter = Instant.now().plus(REDIS_RETRY_DELAY);
        return new ChatMemoryUnavailableException(redisDetail, cause);
    }

    private void checkRedisWhenDue() {
        if (!properties.redisEnabled()) {
            redisAvailable = false;
            redisDetail = "Redis 未启用，当前使用进程内临时会话";
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

    private StoredConversation enforceConversationLimits(StoredConversation conversation) {
        return new StoredConversation(
                conversation.userId(), conversation.conversationId(), conversation.title(),
                conversation.createdAt(), conversation.updatedAt(), conversation.context(),
                keepNewest(conversation.messages(), maxMessagesPerConversation()),
                keepNewest(conversation.artifacts(), maxArtifactsPerConversation()),
                conversation.attributionState());
    }

    private <T> List<T> keepNewest(List<T> values, int maximum) {
        if (values.size() <= maximum) {
            return values;
        }
        return List.copyOf(values.subList(values.size() - maximum, values.size()));
    }

    private int maxMessagesPerConversation() {
        return Math.max(1, properties.maxMessagesPerConversation());
    }

    private int maxArtifactsPerConversation() {
        return Math.max(1, properties.maxArtifactsPerConversation());
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
            QueryContext context, List<ConversationMessage> messages, List<ConversationArtifact> artifacts,
            TemplateConversationState attributionState) {
        private StoredConversation {
            context = context == null ? QueryContext.empty() : context;
            messages = messages == null ? List.of() : List.copyOf(messages);
            artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        }
    }

    public record ConversationSnapshot(
            QueryContext context, List<ConversationMessage> messages,
            List<ConversationArtifact> artifacts, TemplateConversationState attributionState) {
        public ConversationSnapshot {
            context = context == null ? QueryContext.empty() : context;
            messages = messages == null ? List.of() : List.copyOf(messages);
            artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        }

        public ConversationSnapshot(
                QueryContext context, List<ConversationMessage> messages, List<ConversationArtifact> artifacts) {
            this(context, messages, artifacts, null);
        }
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
