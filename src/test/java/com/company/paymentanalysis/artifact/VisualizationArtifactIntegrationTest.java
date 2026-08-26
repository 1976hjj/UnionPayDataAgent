package com.company.paymentanalysis.artifact;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.paymentanalysis.artifact.model.QueryResultArtifactPayload;
import com.company.paymentanalysis.artifact.model.QueryResultArtifactPayload.Column;
import com.company.paymentanalysis.artifact.model.QueryResultArtifactPayload.DataType;
import com.company.paymentanalysis.artifact.model.QueryResultArtifactPayload.QueryContract;
import com.company.paymentanalysis.artifact.model.QueryResultArtifactPayload.Role;
import com.company.paymentanalysis.artifact.service.ArtifactService;
import com.company.paymentanalysis.artifact.service.ArtifactService.CreateQueryResult;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"llm.mock-enabled=true", "chat.memory.redis-enabled=false"})
@AutoConfigureMockMvc
class VisualizationArtifactIntegrationTest {

    @Autowired
    private ArtifactService artifactService;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void visualizationEntryCreatesAReadableChartArtifact() throws Exception {
        QueryResultArtifactPayload payload = new QueryResultArtifactPayload(
                "", List.of(
                        new Column("month", "月份", Role.DIMENSION, DataType.DATE, null),
                        new Column("amount", "金额", Role.METRIC, DataType.NUMBER, "元")),
                List.of(
                        Map.of("month", "2026-01", "amount", new BigDecimal("10.5")),
                        Map.of("month", "2026-02", "amount", new BigDecimal("20"))),
                2, false,
                new QueryContract("dataset", List.of("amount"), List.of("month"), List.of(), List.of()));
        var query = artifactService.createQueryResult(new CreateQueryResult(
                "visual-user", "visual-conversation", null, "月度金额", payload));

        String response = mockMvc.perform(post("/api/agent/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId":"visual-user",
                                  "conversationId":"visual-conversation",
                                  "entryMode":"VISUALIZATION",
                                  "message":"生成折线图",
                                  "visualizationOptions":{
                                    "sourceArtifactId":"%s",
                                    "chartType":"LINE"
                                  }
                                }
                                """.formatted(query.artifactId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skillId").value("visualization"))
                .andExpect(jsonPath("$.viewModel.type").value("chart"))
                .andExpect(jsonPath("$.viewModel.payload.chartType").value("LINE"))
                .andExpect(jsonPath("$.viewModel.payload.categories[1]").value("2026-02"))
                .andReturn().getResponse().getContentAsString();

        String chartId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(response).path("outputArtifactIds").get(0).asText();
        mockMvc.perform(get("/api/artifacts/{artifactId}", chartId).param("userId", "visual-user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.artifactType").value("CHART"))
                .andExpect(jsonPath("$.createdBy").value("visualization-skill"))
                .andExpect(jsonPath("$.payload.sourceArtifactId").value(query.artifactId()))
                .andExpect(jsonPath("$.payload.series[0].values[0]").value(10.5));
    }
}
