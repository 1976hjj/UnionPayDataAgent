package com.company.paymentanalysis.llm;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class OpenAiCompatibleLlmClient {

    private final LlmProperties properties;
    private final RestClient.Builder restClientBuilder;
    private volatile Instant lastSuccessAt;
    private volatile Instant lastFailureAt;

    public OpenAiCompatibleLlmClient(LlmProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.restClientBuilder = restClientBuilder;
    }

    public String complete(List<ChatMessage> messages, String mockContent) {
        return completeWithMessage(messages, mockContent).content();
    }

    public LlmResultMessage completeWithMessage(List<ChatMessage> messages, String mockContent) {
        if (properties.mockEnabled()) {
            return new LlmResultMessage(
                    properties.model(), "assistant", mockContent, List.copyOf(messages));
        }
        if (!StringUtils.hasText(properties.baseUrl()) || !StringUtils.hasText(properties.model())) {
            throw new IllegalStateException("启用真实 LLM 时必须配置 LLM_BASE_URL 和 LLM_MODEL");
        }

        RestClient.RequestBodySpec request = restClientBuilder
                .baseUrl(properties.baseUrl())
                .build()
                .post()
                .uri(properties.chatPath())
                .header(HttpHeaders.CONTENT_TYPE, "application/json");
        if (StringUtils.hasText(properties.apiKey())) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey());
        }

        ChatCompletionRequest body = new ChatCompletionRequest(
                properties.model(),
                messages,
                false,
                properties.jsonMode() ? Map.of("type", "json_object") : null,
                properties.thinkingSupported()
                        ? Map.of("type", properties.thinkingEnabled() ? "enabled" : "disabled")
                        : null,
                properties.maxTokens(),
                properties.temperature());
        JsonNode response = executeWithRetry(request, body);
        JsonNode message = response == null ? null : response.at("/choices/0/message");
        JsonNode content = message == null ? null : message.get("content");
        if (content == null || content.isMissingNode()) {
            lastFailureAt = Instant.now();
            throw new IllegalStateException("LLM 返回中缺少 choices[0].message.content");
        }
        lastSuccessAt = Instant.now();
        String role = message.path("role").asText("assistant");
        return new LlmResultMessage(
                response.path("model").asText(properties.model()),
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
                    lastFailureAt = Instant.now();
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
        return properties.mockEnabled() ? "Mock LLM" : properties.model();
    }

    public boolean isMockEnabled() {
        return properties.mockEnabled();
    }

    public LlmHealth health() {
        if (properties.mockEnabled()) {
            return new LlmHealth("MOCK", "Mock LLM", "使用固定模拟解析", null);
        }
        if (!StringUtils.hasText(properties.baseUrl()) || !StringUtils.hasText(properties.model())) {
            return new LlmHealth("DOWN", "LLM", "缺少模型地址或模型名称", null);
        }
        if (lastFailureAt != null && (lastSuccessAt == null || lastFailureAt.isAfter(lastSuccessAt))) {
            return new LlmHealth("DOWN", properties.model(), "最近一次调用失败", lastFailureAt.toString());
        }
        if (lastSuccessAt != null) {
            return new LlmHealth("UP", properties.model(), "最近一次调用成功", lastSuccessAt.toString());
        }
        return new LlmHealth("READY", properties.model(), "配置完成，等待首次调用", null);
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
