package com.company.paymentanalysis.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.paymentanalysis.artifact.model.Artifact;
import com.company.paymentanalysis.artifact.model.Artifact.Status;
import com.company.paymentanalysis.artifact.model.Artifact.StorageType;
import com.company.paymentanalysis.artifact.model.ArtifactType;
import com.company.paymentanalysis.artifact.model.ChartArtifactPayload;
import com.company.paymentanalysis.artifact.model.ChartArtifactPayload.ChartType;
import com.company.paymentanalysis.artifact.model.QueryResultArtifactPayload;
import com.company.paymentanalysis.artifact.model.QueryResultArtifactPayload.Column;
import com.company.paymentanalysis.artifact.model.QueryResultArtifactPayload.DataType;
import com.company.paymentanalysis.artifact.model.QueryResultArtifactPayload.QueryContract;
import com.company.paymentanalysis.artifact.model.QueryResultArtifactPayload.Role;
import com.company.paymentanalysis.artifact.service.ArtifactAccessService;
import com.company.paymentanalysis.artifact.service.ArtifactService;
import com.company.paymentanalysis.artifact.service.ArtifactService.CreateChart;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class VisualizationSkillTest {

    @Test
    void createsALineChartArtifactFromAnOwnedQueryResult() {
        ArtifactAccessService accessService = mock(ArtifactAccessService.class);
        ArtifactService artifactService = mock(ArtifactService.class);
        QueryResultArtifactPayload query = queryResult();
        when(accessService.readPayload(
                "user", "art_query", ArtifactType.QUERY_RESULT, QueryResultArtifactPayload.class))
                .thenReturn(query);
        when(artifactService.createChart(any())).thenReturn(artifact("art_chart"));
        VisualizationSkill skill = new VisualizationSkill(accessService, artifactService);

        AgentResponse response = skill.execute(
                request(new VisualizationOptions("art_query", ChartType.AUTO, null, List.of(), null)),
                context());

        ArgumentCaptor<CreateChart> command = ArgumentCaptor.forClass(CreateChart.class);
        verify(artifactService).createChart(command.capture());
        ChartArtifactPayload payload = command.getValue().payload();
        assertThat(payload.chartType()).isEqualTo(ChartType.LINE);
        assertThat(payload.dimensionId()).isEqualTo("month");
        assertThat(payload.categories()).containsExactly("2026-01", "2026-02");
        assertThat(payload.series()).hasSize(1);
        assertThat(payload.series().get(0).values())
                .containsExactly(new BigDecimal("12.5"), new BigDecimal("18"));
        assertThat(command.getValue().sourceArtifactId()).isEqualTo("art_query");
        assertThat(response.skillId()).isEqualTo("visualization");
        assertThat(response.outputArtifactIds()).containsExactly("art_chart");
    }

    @Test
    void rejectsResultsWithoutDimensionsAndPieChartsWithMultipleMetrics() {
        ArtifactAccessService accessService = mock(ArtifactAccessService.class);
        ArtifactService artifactService = mock(ArtifactService.class);
        VisualizationSkill skill = new VisualizationSkill(accessService, artifactService);
        QueryResultArtifactPayload noDimension = new QueryResultArtifactPayload(
                "", List.of(new Column("amount", "金额", Role.METRIC, DataType.NUMBER, "元")),
                List.of(Map.of("amount", 1)), 1, false,
                new QueryContract("ds", List.of("amount"), List.of(), List.of(), List.of()));
        when(accessService.readPayload(
                "user", "art_no_dimension", ArtifactType.QUERY_RESULT, QueryResultArtifactPayload.class))
                .thenReturn(noDimension);

        assertThatThrownBy(() -> skill.execute(
                request(new VisualizationOptions(
                        "art_no_dimension", ChartType.LINE, null, List.of(), null)), context()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("维度");

        QueryResultArtifactPayload twoMetrics = new QueryResultArtifactPayload(
                "", List.of(
                        new Column("month", "月份", Role.DIMENSION, DataType.DATE, null),
                        new Column("amount", "金额", Role.METRIC, DataType.NUMBER, "元"),
                        new Column("count", "笔数", Role.METRIC, DataType.NUMBER, "笔")),
                List.of(Map.of("month", "2026-01", "amount", 1, "count", 2)), 1, false,
                new QueryContract("ds", List.of("amount", "count"), List.of("month"), List.of(), List.of()));
        when(accessService.readPayload(
                "user", "art_two_metrics", ArtifactType.QUERY_RESULT, QueryResultArtifactPayload.class))
                .thenReturn(twoMetrics);

        assertThatThrownBy(() -> skill.execute(
                request(new VisualizationOptions(
                        "art_two_metrics", ChartType.PIE, null, List.of(), null)), context()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("一个度量");
    }

    private QueryResultArtifactPayload queryResult() {
        return new QueryResultArtifactPayload(
                "", List.of(
                        new Column("month", "月份", Role.DIMENSION, DataType.DATE, null),
                        new Column("amount", "金额", Role.METRIC, DataType.NUMBER, "元")),
                List.of(
                        Map.of("month", "2026-01", "amount", new BigDecimal("12.5")),
                        Map.of("month", "2026-02", "amount", 18)),
                2, false,
                new QueryContract("ds", List.of("amount"), List.of("month"), List.of(), List.of()));
    }

    private AgentRequest request(VisualizationOptions options) {
        return new AgentRequest(
                "user", "conversation", "生成图表", AgentEntryMode.VISUALIZATION, "model", false,
                null, null, null, options, AgentAction.MESSAGE);
    }

    private AgentContext context() {
        return new AgentContext(
                "user", "conversation", AgentEntryMode.VISUALIZATION, "model", false,
                null, null, null, AgentAction.MESSAGE);
    }

    private Artifact artifact(String id) {
        return new Artifact(
                id, ArtifactType.CHART, 1, Status.COMPLETED, "图表", "user", "conversation",
                null, "visualization-skill", StorageType.INLINE, "{}", null, "checksum", 2L,
                Instant.now(), null);
    }
}
