package com.company.paymentanalysis.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.paymentanalysis.artifact.model.QueryResultArtifactPayload;
import com.company.paymentanalysis.artifact.model.QueryResultArtifactPayload.Column;
import com.company.paymentanalysis.artifact.model.QueryResultArtifactPayload.DataType;
import com.company.paymentanalysis.artifact.model.QueryResultArtifactPayload.QueryContract;
import com.company.paymentanalysis.artifact.model.QueryResultArtifactPayload.Role;
import com.company.paymentanalysis.artifact.service.ArtifactService;
import com.company.paymentanalysis.artifact.service.ArtifactService.CreateQueryResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "chat.memory.redis-enabled=false")
@AutoConfigureMockMvc
class ExportArtifactIntegrationTest {

    @Autowired
    private ArtifactService artifactService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void exportsAndDownloadsAnOwnerScopedCsvFileArtifact() throws Exception {
        var query = artifactService.createQueryResult(new CreateQueryResult(
                "export-user", "export-conversation", null, "交易明细", queryResult()));
        String plannerResponse = mockMvc.perform(post("/api/agent/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId":"export-user",
                                  "conversationId":"export-conversation",
                                  "message":"下载 CSV 原始数据",
                                  "parameters":{"fileName":"交易明细"}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeSkill").value("TOOL"))
                .andExpect(jsonPath("$.plan.plannerId").value("deterministic-supervisor"))
                .andExpect(jsonPath("$.plan.steps[0].capabilityId").value("export-data"))
                .andExpect(jsonPath("$.plan.steps[0].inputArtifactIds[0]").value(query.artifactId()))
                .andReturn().getResponse().getContentAsString();
        String fileArtifactId = objectMapper.readTree(plannerResponse)
                .path("outputArtifactIds").get(0).asText();

        mockMvc.perform(get("/api/artifacts/{artifactId}", fileArtifactId).param("userId", "export-user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artifactType").value("FILE"))
                .andExpect(jsonPath("$.storageType").value("FILE"))
                .andExpect(jsonPath("$.payload.fileName").value("交易明细.csv"))
                .andExpect(jsonPath("$.payload.sourceArtifactId").value(query.artifactId()))
                .andExpect(jsonPath("$.payloadUri").doesNotExist());

        byte[] downloaded = mockMvc.perform(get("/api/artifacts/{artifactId}/download", fileArtifactId)
                        .param("userId", "export-user"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(downloaded, StandardCharsets.UTF_8))
                .contains("月份", "人民币承兑金额", "2026-01", "10.5");

        mockMvc.perform(get("/api/artifacts/{artifactId}/download", fileArtifactId)
                        .param("userId", "another-user"))
                .andExpect(status().isNotFound());
    }

    private QueryResultArtifactPayload queryResult() {
        return new QueryResultArtifactPayload(
                "", List.of(
                        new Column("month", "月份", Role.DIMENSION, DataType.STRING, null),
                        new Column("amount", "人民币承兑金额", Role.METRIC, DataType.NUMBER, "元")),
                List.of(Map.of("month", "2026-01", "amount", new BigDecimal("10.50"))),
                1, false,
                new QueryContract("dataset", List.of("amount"), List.of("month"), List.of(), List.of()));
    }
}
