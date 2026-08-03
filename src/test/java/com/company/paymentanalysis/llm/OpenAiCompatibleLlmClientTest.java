package com.company.paymentanalysis.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class OpenAiCompatibleLlmClientTest {

    private final OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(
            new LlmProperties(
                    false, "https://open.bigmodel.cn", "", "glm-4.7-flash",
                    List.of("glm-4.7", "glm-4.7-flashx", "glm-4.7-flash", "glm-4-flash-250414"),
                    "/api/paas/v4/chat/completions", true, false, false, 512, 0, 1, 0),
            RestClient.builder());

    @Test
    void resolvesOnlyConfiguredModelsAndKeepsTheirOrder() {
        assertThat(client.defaultModel()).isEqualTo("glm-4.7-flash");
        assertThat(client.supportedModels())
                .containsExactly("glm-4.7", "glm-4.7-flashx", "glm-4.7-flash", "glm-4-flash-250414");
        assertThat(client.resolveModel("glm-4.7-flashx")).isEqualTo("glm-4.7-flashx");
        assertThat(client.resolveModel("glm-4-flash-250414")).isEqualTo("glm-4-flash-250414");
        assertThatThrownBy(() -> client.resolveModel("unconfigured-model"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的 LLM 模型");
    }

    @Test
    void reportsHealthForOnlyTheRequestedModelWithoutCallingIt() {
        var health = client.health("glm-4.7");

        assertThat(health.name()).isEqualTo("glm-4.7");
        assertThat(health.status()).isEqualTo("READY");
        assertThat(health.detail()).contains("尚未调用");
    }
}
