package com.company.paymentanalysis.smartbi;

import com.company.paymentanalysis.smartbi.SmartBiModels.QueryRequest;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import smartbi.net.sf.json.JSONObject;
import smartbi.sdk.ClientConnector;
import smartbi.sdk.InvokeResult;

@Component
public class SmartBiClient {

    private static final String USER = "admin";
    private static final String PASSWORD = "bi15*Sm31tH";

    // 测试和本地环境
    private static final String URL = "http://172.19.216.143:8080/smartbi";
    // 生产环境
    // private static final String URL = "http://172.19.208.144:8080/smartbi";

    private static final String MODULE = "AugmentedDataSetForVModule";
    private static final String METHOD = "getData";

    private final ObjectMapper objectMapper;
    private final SmartBiProperties properties;
    private final RestClient mockRestClient;
    private final SmartBiResponseAdapter responseAdapter;
    private volatile Instant lastSuccessAt;
    private volatile Instant lastFailureAt;

    public SmartBiClient(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            SmartBiProperties properties,
            SmartBiResponseAdapter responseAdapter) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.mockRestClient = builder.baseUrl(properties.mockBaseUrl()).build();
        this.responseAdapter = responseAdapter;
    }

    public QueryResponse query(QueryRequest request) {
        if (properties.mockEnabled()) {
            return mockQuery(request);
        }
        ClientConnector conn = new ClientConnector(URL);
        try {
            if (!conn.open(USER, PASSWORD)) {
                throw new IllegalStateException("SmartBI admin 账号登录失败");
            }

            JSONObject queryJson = toSmartBiJson(request);
            InvokeResult invokeResult = conn.remoteInvoke(MODULE, METHOD, new Object[]{queryJson});
            Object result = invokeResult.getResult();
            if (result == null) {
                throw new IllegalStateException("SmartBI 返回为空");
            }

            JsonNode response = objectMapper.readTree(result.toString());
            QueryResponse normalized = responseAdapter.normalize(response);
            lastSuccessAt = Instant.now();
            return normalized;
        } catch (Exception exception) {
            lastFailureAt = Instant.now();
            throw new IllegalStateException("SmartBI 查询失败: " + exception.getMessage(), exception);
        } finally {
            conn.close();
        }
    }

    public SmartBiHealth health() {
        if (properties.mockEnabled()) {
            return new SmartBiHealth(
                    "MOCK", "Mock SmartBI", "使用应用内 Mock 数据，不会连接或登录真实 SmartBI", null);
        }
        if (lastFailureAt != null && (lastSuccessAt == null || lastFailureAt.isAfter(lastSuccessAt))) {
            return new SmartBiHealth("DOWN", "SmartBI", "最近一次查询失败", lastFailureAt.toString());
        }
        if (lastSuccessAt != null) {
            return new SmartBiHealth("UP", "SmartBI", "最近一次查询成功", lastSuccessAt.toString());
        }
        return new SmartBiHealth("READY", "SmartBI", "admin 直连已配置，等待首次查询", null);
    }

    private QueryResponse mockQuery(QueryRequest request) {
        try {
            JsonNode response = mockRestClient.post()
                    .uri("/api/mock/smartbi/query")
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null || response.isNull()) {
                throw new IllegalStateException("Mock SmartBI 返回为空");
            }
            QueryResponse normalized = responseAdapter.normalize(response);
            lastSuccessAt = Instant.now();
            return normalized;
        } catch (RuntimeException exception) {
            lastFailureAt = Instant.now();
            throw exception;
        }
    }

    private JSONObject toSmartBiJson(QueryRequest request) throws JsonProcessingException {
        JSONObject json = JSONObject.fromObject(objectMapper.writeValueAsString(request));
        if (!json.has("rowsPerPage")) {
            json.put("rowsPerPage", 9_999_999);
        }
        return json;
    }

    public record SmartBiHealth(String status, String name, String detail, String checkedAt) {
    }
}
