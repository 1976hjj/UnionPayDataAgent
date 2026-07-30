package com.company.paymentanalysis.smartbi;

import com.company.paymentanalysis.smartbi.SmartBiModels.QueryRequest;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryResponse;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.function.Consumer;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Component
public class SmartBiClient {

    private final RestClient restClient;
    private final SmartBiProperties properties;
    private final SmartBiResponseAdapter responseAdapter;
    private volatile Instant lastSuccessAt;
    private volatile Instant lastFailureAt;

    public SmartBiClient(
            RestClient.Builder builder, SmartBiProperties properties, SmartBiResponseAdapter responseAdapter) {
        this.properties = properties;
        this.responseAdapter = responseAdapter;
        this.restClient =
                builder.baseUrl(properties.baseUrl()).defaultHeaders(defaultHeaders(properties)).build();
    }

    public QueryResponse query(QueryRequest request) {
        try {
            JsonNode response = restClient.post()
                    .uri(properties.queryPath())
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null || response.isNull()) {
                throw new IllegalStateException("SmartBI 返回为空");
            }
            QueryResponse normalized = responseAdapter.normalize(response);
            lastSuccessAt = Instant.now();
            return normalized;
        } catch (RuntimeException exception) {
            lastFailureAt = Instant.now();
            throw exception;
        }
    }

    public SmartBiHealth health() {
        String name = properties.queryPath().contains("/api/mock/") ? "Mock SmartBI" : "SmartBI";
        if (lastFailureAt != null && (lastSuccessAt == null || lastFailureAt.isAfter(lastSuccessAt))) {
            return new SmartBiHealth("DOWN", name, "最近一次查询失败", lastFailureAt.toString());
        }
        if (lastSuccessAt != null) {
            return new SmartBiHealth("UP", name, "最近一次查询成功", lastSuccessAt.toString());
        }
        if (properties.queryPath().contains("/api/mock/")) {
            return new SmartBiHealth("MOCK", name, "当前使用应用内模拟接口", null);
        }
        return new SmartBiHealth("READY", name, "配置完成，等待首次查询", null);
    }

    private Consumer<HttpHeaders> defaultHeaders(SmartBiProperties properties) {
        return headers -> {
            if (StringUtils.hasText(properties.sessionCookie())) {
                headers.set(HttpHeaders.COOKIE, properties.sessionCookie());
            }
            if (StringUtils.hasText(properties.authorization())) {
                headers.set(HttpHeaders.AUTHORIZATION, properties.authorization());
            }
        };
    }

    public record SmartBiHealth(String status, String name, String detail, String checkedAt) {
    }
}
