package com.company.paymentanalysis.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class OpenAiCompatibleLlmClientTest {

    private final OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(
            new LlmProperties(
                    false, "https://open.bigmodel.cn", "", "deepseek-v3",
                    List.of("glm-4.7", "glm-4.7-flashx", "glm-4.7-flash", "glm-4-flash-250414"),
                    "/api/paas/v4/chat/completions", true, false, false, 512, 0, 1, 0,
                    List.of(
                            new LlmProperties.ModelProfile(
                                    "deepseek-v3", "DeepSeek-V3（公司）", "deepseek-v3",
                                    "http://172.19.209.4:32000/v1", "/chat/completions",
                                    false, false, false, 512, 0.0),
                            new LlmProperties.ModelProfile(
                                    "glm-4.6-fp8", "GLM-4.6-FP8（公司）", "glm-4.6-fp8",
                                    "http://172.19.209.6:32002/v1", "/chat/completions",
                                    false, false, false, 512, 0.0))),
            RestClient.builder());

    @Test
    void resolvesOnlyConfiguredModelsAndKeepsTheirOrder() {
        assertThat(client.defaultModel()).isEqualTo("deepseek-v3");
        assertThat(client.supportedModels())
                .containsExactly("deepseek-v3", "glm-4.6-fp8");
        assertThat(client.resolveModel("glm-4.6-fp8")).isEqualTo("glm-4.6-fp8");
        assertThat(client.resolveSelection("deepseek-v3")).isEqualTo("deepseek-v3");
        assertThat(client.supportedProfiles().get(0).baseUrl())
                .isEqualTo("http://172.19.209.4:32000/v1");
        assertThat(client.supportedProfiles().get(1).chatPath()).isEqualTo("/chat/completions");
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
}
