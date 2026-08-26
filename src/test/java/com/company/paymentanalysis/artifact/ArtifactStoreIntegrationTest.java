package com.company.paymentanalysis.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.paymentanalysis.artifact.model.ArtifactType;
import com.company.paymentanalysis.artifact.model.QueryResultArtifactPayload;
import com.company.paymentanalysis.artifact.model.QueryResultArtifactPayload.Column;
import com.company.paymentanalysis.artifact.model.QueryResultArtifactPayload.DataType;
import com.company.paymentanalysis.artifact.model.QueryResultArtifactPayload.QueryContract;
import com.company.paymentanalysis.artifact.model.QueryResultArtifactPayload.Role;
import com.company.paymentanalysis.artifact.service.ArtifactService;
import com.company.paymentanalysis.artifact.service.ArtifactService.CreateQueryResult;
import com.company.paymentanalysis.artifact.store.ArtifactStore;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "chat.memory.redis-enabled=false")
@AutoConfigureMockMvc
class ArtifactStoreIntegrationTest {

    @Autowired
    private ArtifactService artifactService;

    @Autowired
    private ArtifactStore artifactStore;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void savesTypedQueryPayloadAndReadsItOnlyForItsOwner() throws Exception {
        QueryResultArtifactPayload payload = new QueryResultArtifactPayload(
                "查询完成，共返回 1 条数据。",
                List.of(new Column("trans_cnt_m", "总交易笔数", Role.METRIC, DataType.NUMBER, null)),
                List.of(Map.of("trans_cnt_m", new BigDecimal("123"))),
                1,
                false,
                new QueryContract("dataset", List.of("trans_cnt_m"), List.of(), List.of(), List.of()));

        var saved = artifactService.createQueryResult(new CreateQueryResult(
                "artifact-test-user", "artifact-test-conversation", null, "总交易笔数查询结果", payload));

        var loaded = artifactStore.findById(saved.artifactId()).orElseThrow();
        assertThat(loaded.artifactType()).isEqualTo(ArtifactType.QUERY_RESULT);
        assertThat(loaded.payloadChecksum()).hasSize(64);
        assertThat(loaded.payloadJson()).contains("trans_cnt_m", "123");
        assertThat(loaded.rowCount()).isEqualTo(1);
        assertThat(artifactStore.findByConversation(
                "artifact-test-user", "artifact-test-conversation"))
                .extracting(value -> value.artifactId())
                .contains(saved.artifactId());

        mockMvc.perform(get("/api/artifacts/{artifactId}", saved.artifactId())
                        .param("userId", "artifact-test-user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artifactId").value(saved.artifactId()))
                .andExpect(jsonPath("$.artifactType").value("QUERY_RESULT"))
                .andExpect(jsonPath("$.payload.rows[0].trans_cnt_m").value(123));

        mockMvc.perform(get("/api/artifacts/{artifactId}", saved.artifactId())
                        .param("userId", "another-user"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/artifacts/conversations/{conversationId}",
                        "artifact-test-conversation")
                        .param("userId", "artifact-test-user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].payload").doesNotExist());
    }
}
