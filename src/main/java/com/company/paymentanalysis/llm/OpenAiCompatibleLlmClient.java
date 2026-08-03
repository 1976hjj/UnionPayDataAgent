package com.company.paymentanalysis.llm;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class OpenAiCompatibleLlmClient {

    private final LlmProperties properties;
    private final RestClient.Builder restClientBuilder;
    private final Map<String, Instant> lastSuccessAt = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastFailureAt = new ConcurrentHashMap<>();

    public OpenAiCompatibleLlmClient(LlmProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.restClientBuilder = restClientBuilder;
    }

    public String complete(List<ChatMessage> messages, String mockContent) {
        return completeWithMessage(messages, mockContent).content();
    }

    public LlmResultMessage completeWithMessage(List<ChatMessage> messages, String mockContent) {
        return completeWithMessage(messages, mockContent, null);
    }

    public LlmResultMessage completeWithMessage(
            List<ChatMessage> messages, String mockContent, String requestedModel) {
        LlmProperties.ModelProfile profile = resolveProfile(requestedModel);
        String model = profile.model();
        if (properties.mockEnabled()) {
            return new LlmResultMessage(model, "assistant", mockContent, List.copyOf(messages));
        }
        if (!StringUtils.hasText(profile.baseUrl()) || !StringUtils.hasText(model)) {
            throw new IllegalStateException("启用真实 LLM 时必须配置模型地址和模型名称");
        }

        RestClient.RequestBodySpec request = restClientBuilder
                .baseUrl(profile.baseUrl())
                .build()
                .post()
                .uri(profile.chatPath())
                .header(HttpHeaders.CONTENT_TYPE, "application/json");
        if (StringUtils.hasText(properties.apiKey())) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey());
        }

        ChatCompletionRequest body = new ChatCompletionRequest(
                model,
                messages,
                false,
                profile.jsonMode() ? Map.of("type", "json_object") : null,
                profile.thinkingSupported()
                        ? Map.of("type", profile.thinkingEnabled() ? "enabled" : "disabled")
                        : null,
                profile.maxTokens(),
                profile.temperature());
        JsonNode response;
        try {
            response = executeWithRetry(request, body);
        } catch (RuntimeException exception) {
            lastFailureAt.put(profile.id(), Instant.now());
            throw exception;
        }
        JsonNode message = response == null ? null : response.at("/choices/0/message");
        JsonNode content = message == null ? null : message.get("content");
        if (content == null || content.isMissingNode()) {
            lastFailureAt.put(profile.id(), Instant.now());
            throw new IllegalStateException("LLM 返回中缺少 choices[0].message.content");
        }
        lastSuccessAt.put(profile.id(), Instant.now());
        String role = message.path("role").asText("assistant");
        return new LlmResultMessage(
                response.path("model").asText(model),
                role,
                content.asText(),
                List.copyOf(messages),
                response.toPrettyString());
    }

    private JsonNode executeWithRetry(RestClient.RequestBodySpec request, ChatCompletionRequest body) {
        int attempts = Math.max(1, properties.maxAttempts());
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return request.body(body).retrieve().body(JsonNode.class);
            } catch (RestClientResponseException exception) {
                boolean retryable = exception.getStatusCode().value() == 429
                        || exception.getStatusCode().is5xxServerError();
                if (!retryable || attempt == attempts) {
                    throw new IllegalStateException(
                            "LLM 调用失败（HTTP " + exception.getStatusCode().value() + "）："
                                    + exception.getResponseBodyAsString(),
                            exception);
                }
                waitBeforeRetry(attempt);
            }
        }
        throw new IllegalStateException("LLM 调用未返回结果");
    }

    private void waitBeforeRetry(int attempt) {
        try {
            Thread.sleep(Math.max(0, properties.retryDelayMs()) * attempt);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待重试 LLM 时线程被中断", exception);
        }
    }

    public String modelLabel() {
        return properties.mockEnabled() ? "Mock LLM" : resolveProfile(null).displayName();
    }

    public String modelLabel(String requestedModel) {
        if (!StringUtils.hasText(requestedModel)) {
            return modelLabel();
        }
        LlmProperties.ModelProfile profile = resolveProfile(requestedModel);
        return properties.mockEnabled() ? "Mock LLM（" + profile.displayName() + "）" : profile.displayName();
    }

    public boolean isMockEnabled() {
        return properties.mockEnabled();
    }

    public LlmHealth health() {
        return health(null);
    }

    public LlmHealth health(String requestedModel) {
        LlmProperties.ModelProfile profile = resolveProfile(requestedModel);
        if (properties.mockEnabled()) {
            return new LlmHealth("MOCK", "Mock LLM（" + profile.displayName() + "）", "使用固定模拟解析", null);
        }
        if (!StringUtils.hasText(profile.baseUrl()) || !StringUtils.hasText(profile.model())) {
            return new LlmHealth("DOWN", profile.displayName(), "缺少模型地址或模型名称", null);
        }
        Instant successAt = lastSuccessAt.get(profile.id());
        Instant failureAt = lastFailureAt.get(profile.id());
        if (failureAt != null && (successAt == null || failureAt.isAfter(successAt))) {
            return new LlmHealth("DOWN", profile.displayName(), "最近一次调用失败", failureAt.toString());
        }
        if (successAt != null) {
            return new LlmHealth("UP", profile.displayName(), "最近一次调用成功", successAt.toString());
        }
        return new LlmHealth("READY", profile.displayName(), "已配置，尚未调用该模型", null);
    }

    public List<String> supportedModels() {
        return supportedProfiles().stream().map(LlmProperties.ModelProfile::id).toList();
    }

    public List<LlmProperties.ModelProfile> supportedProfiles() {
        LinkedHashMap<String, LlmProperties.ModelProfile> profiles = new LinkedHashMap<>();
        if (properties.profiles() != null) {
            properties.profiles().stream()
                    .filter(profile -> profile != null && StringUtils.hasText(profile.id()))
                    .map(this::normalizeProfile)
                    .forEach(profile -> profiles.putIfAbsent(profile.id(), profile));
        }
        if (profiles.isEmpty()) {
            legacyProfiles().forEach(profile -> profiles.putIfAbsent(profile.id(), profile));
        }
        return List.copyOf(profiles.values());
    }

    public String defaultModel() {
        return resolveProfile(null).id();
    }

    public String resolveModel(String requestedModel) {
        return resolveProfile(requestedModel).model();
    }

    public String resolveSelection(String requestedModel) {
        return resolveProfile(requestedModel).id();
    }

    private LlmProperties.ModelProfile resolveProfile(String requestedModel) {
        String selection = StringUtils.hasText(requestedModel) ? requestedModel.trim() : properties.model();
        return supportedProfiles().stream()
                .filter(profile -> profile.id().equals(selection))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的 LLM 模型：" + selection));
    }

    private List<LlmProperties.ModelProfile> legacyProfiles() {
        LinkedHashSet<String> models = new LinkedHashSet<>();
        if (properties.models() != null) {
            properties.models().stream().filter(StringUtils::hasText).map(String::trim).forEach(models::add);
        }
        if (StringUtils.hasText(properties.model())) {
            models.add(properties.model().trim());
        }
        List<LlmProperties.ModelProfile> profiles = new ArrayList<>();
        for (String model : models) {
            profiles.add(new LlmProperties.ModelProfile(
                    model,
                    model,
                    model,
                    properties.baseUrl(),
                    properties.chatPath(),
                    properties.jsonMode(),
                    properties.thinkingSupported(),
                    properties.thinkingEnabled(),
                    properties.maxTokens(),
                    properties.temperature()));
        }
        return profiles;
    }

    private LlmProperties.ModelProfile normalizeProfile(LlmProperties.ModelProfile profile) {
        String id = profile.id().trim();
        String model = StringUtils.hasText(profile.model()) ? profile.model().trim() : id;
        String displayName = StringUtils.hasText(profile.displayName()) ? profile.displayName().trim() : model;
        String baseUrl = StringUtils.hasText(profile.baseUrl()) ? profile.baseUrl().trim() : properties.baseUrl();
        String chatPath = StringUtils.hasText(profile.chatPath()) ? profile.chatPath().trim() : properties.chatPath();
        boolean jsonMode = profile.jsonMode() == null ? properties.jsonMode() : profile.jsonMode();
        boolean thinkingSupported = profile.thinkingSupported() == null
                ? properties.thinkingSupported()
                : profile.thinkingSupported();
        boolean thinkingEnabled = profile.thinkingEnabled() == null
                ? properties.thinkingEnabled()
                : profile.thinkingEnabled();
        int maxTokens = profile.maxTokens() == null ? properties.maxTokens() : profile.maxTokens();
        double temperature = profile.temperature() == null ? properties.temperature() : profile.temperature();
        return new LlmProperties.ModelProfile(
                id, displayName, model, baseUrl, chatPath, jsonMode, thinkingSupported,
                thinkingEnabled, maxTokens, temperature);
    }

    public record ChatMessage(String role, String content) implements Serializable {
    }

    public record LlmResultMessage(
            String model,
            String role,
            String content,
            List<ChatMessage> requestMessages,
            String rawResponse)
            implements Serializable {
        public LlmResultMessage(
                String model, String role, String content, List<ChatMessage> requestMessages) {
            this(model, role, content, requestMessages, null);
        }
    }

    public record LlmHealth(String status, String name, String detail, String checkedAt) {
    }

    public record ChatCompletionRequest(
            String model, List<ChatMessage> messages, boolean stream,
            Map<String, String> response_format, Map<String, String> thinking,
            int max_tokens, double temperature) {
    }
}
