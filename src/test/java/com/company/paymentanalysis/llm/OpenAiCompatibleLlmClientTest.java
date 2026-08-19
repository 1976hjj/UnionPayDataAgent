package com.company.paymentanalysis.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.paymentanalysis.audit.ProcessAuditLog;
import com.company.paymentanalysis.audit.ProcessAuditProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class OpenAiCompatibleLlmClientTest {

    private final OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(
            new LlmProperties(
                    false, "https://open.bigmodel.cn", "", "deepseek-v4-flash",
                    List.of(),
                    "/api/paas/v4/chat/completions", true, false, false, 512, 0, 1, 0,
                    List.of(
                            new LlmProperties.ModelProfile(
                                    "deepseek-v4-flash", "DeepSeek-V4-Flash（公司）", "deepseek-v4-flash",
                                    "http://172.19.209.4:32001/v1", "/chat/completions",
                                    false, false, false, 512, 0.0),
                            new LlmProperties.ModelProfile(
                                    "glm-5.2", "GLM-5.2（本机）", "glm-5.2",
                                    "https://open.bigmodel.cn", "/api/paas/v4/chat/completions",
                                    true, true, false, 512, 0.0),
                            new LlmProperties.ModelProfile(
                                    "glm-4.6-fp8", "GLM-4.6-FP8（公司）", "glm-4.6-fp8",
                                    "http://172.19.209.6:32002/v1", "/chat/completions",
                                    false, false, false, 512, 0.0))),
            RestClient.builder(), auditLog());

    @Test
    void resolvesOnlyConfiguredModelsAndKeepsTheirOrder() {
        assertThat(client.defaultModel()).isEqualTo("deepseek-v4-flash");
        assertThat(client.supportedModels())
                .containsExactly("deepseek-v4-flash", "glm-5.2", "glm-4.6-fp8");
        assertThat(client.resolveModel("glm-4.6-fp8")).isEqualTo("glm-4.6-fp8");
        assertThat(client.resolveSelection("deepseek-v4-flash")).isEqualTo("deepseek-v4-flash");
        assertThat(client.supportedProfiles().get(0).baseUrl())
                .isEqualTo("http://172.19.209.4:32001/v1");
        assertThat(client.supportedProfiles().get(1).chatPath()).isEqualTo("/api/paas/v4/chat/completions");
        assertThat(client.supportedProfiles().get(2).chatPath()).isEqualTo("/chat/completions");
        assertThatThrownBy(() -> client.resolveModel("unconfigured-model"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的 LLM 模型");
    }

    @Test
    void reportsHealthForOnlyTheRequestedModelWithoutCallingIt() {
        var health = client.health("glm-4.6-fp8");

        assertThat(health.name()).isEqualTo("GLM-4.6-FP8（公司）");
        assertThat(health.status()).isEqualTo("READY");
        assertThat(health.detail()).contains("尚未调用");
    }

    @Test
    void acceptsAProviderModelNameAsTheConfiguredDefault() {
        OpenAiCompatibleLlmClient providerModelDefault = new OpenAiCompatibleLlmClient(
                new LlmProperties(
                        false, "https://open.bigmodel.cn", "", "glm-4-flash-250414",
                        List.of(), "/api/paas/v4/chat/completions", true, false, false, 512, 0, 1, 0,
                        List.of(new LlmProperties.ModelProfile(
                                "glm-flash", "GLM Flash", "glm-4-flash-250414",
                                "https://open.bigmodel.cn", "/api/paas/v4/chat/completions",
                                true, false, false, 512, 0.0))),
                RestClient.builder(), auditLog());

        assertThat(providerModelDefault.defaultModel()).isEqualTo("glm-flash");
    }
    private static ProcessAuditLog auditLog() {
        return new ProcessAuditLog(new ObjectMapper(), new ProcessAuditProperties(false, "target/test-audit.jsonl", 1_000));
    }
}
