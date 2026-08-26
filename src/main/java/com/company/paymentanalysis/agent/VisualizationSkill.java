package com.company.paymentanalysis.agent;

import com.company.paymentanalysis.artifact.model.Artifact;
import com.company.paymentanalysis.artifact.model.ArtifactType;
import com.company.paymentanalysis.artifact.model.ChartArtifactPayload;
import com.company.paymentanalysis.artifact.model.ChartArtifactPayload.ChartType;
import com.company.paymentanalysis.artifact.model.ChartArtifactPayload.Series;
import com.company.paymentanalysis.artifact.model.QueryResultArtifactPayload;
import com.company.paymentanalysis.artifact.model.QueryResultArtifactPayload.Column;
import com.company.paymentanalysis.artifact.model.QueryResultArtifactPayload.Role;
import com.company.paymentanalysis.artifact.service.ArtifactAccessService;
import com.company.paymentanalysis.artifact.service.ArtifactService;
import com.company.paymentanalysis.artifact.service.ArtifactService.CreateChart;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Converts a typed query result into a renderer-neutral chart artifact. */
@Service
public class VisualizationSkill implements AgentSkill {

    private static final SkillDescriptor DESCRIPTOR = new SkillDescriptor(
            "visualization", "图表生成", "将查询结果转换为折线图、柱状图或饼图",
            AgentEntryMode.VISUALIZATION,
            Set.of(AgentAction.MESSAGE),
            Set.of(ArtifactType.QUERY_RESULT), Set.of(ArtifactType.CHART), false);

    private final ArtifactAccessService artifactAccessService;
    private final ArtifactService artifactService;

    public VisualizationSkill(
            ArtifactAccessService artifactAccessService,
            ArtifactService artifactService) {
        this.artifactAccessService = artifactAccessService;
        this.artifactService = artifactService;
    }

    @Override
    public SkillDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public AgentResponse execute(AgentRequest request, AgentContext context) {
        VisualizationOptions options = request.visualizationOptions();
        if (options == null || blank(options.sourceArtifactId())) {
            throw new IllegalArgumentException("生成图表需要来源查询 Artifact");
        }
        QueryResultArtifactPayload query = artifactAccessService.readPayload(
                context.userId(), options.sourceArtifactId(), ArtifactType.QUERY_RESULT,
                QueryResultArtifactPayload.class);

        Column dimension = selectDimension(query.columns(), options.dimensionId());
        List<Column> metrics = selectMetrics(query.columns(), options.metricIds());
        ChartType chartType = options.chartType() == ChartType.AUTO ? ChartType.LINE : options.chartType();
        if (chartType == ChartType.PIE && metrics.size() != 1) {
            throw new IllegalArgumentException("饼图必须且只能选择一个度量");
        }

        List<String> categories = query.rows().stream()
                .map(row -> display(row.get(dimension.id())))
                .toList();
        List<Series> series = metrics.stream()
                .map(metric -> new Series(
                        metric.id(), metric.displayName(), metric.unit(),
                        query.rows().stream().map(row -> number(row.get(metric.id()))).toList()))
                .toList();
        String title = blank(options.title()) ? defaultTitle(metrics, dimension) : options.title().trim();
        ChartArtifactPayload payload = new ChartArtifactPayload(
                options.sourceArtifactId(), chartType, dimension.id(), dimension.displayName(), categories, series);
        Artifact artifact = artifactService.createChart(new CreateChart(
                context.userId(), context.conversationId(), null, title,
                options.sourceArtifactId(), payload));
        return new AgentResponse(
                "completed", "VISUALIZATION", "图表已生成", context.conversationId(),
                new AgentViewModel("chart", payload), DESCRIPTOR.skillId(), List.of(artifact.artifactId()));
    }

    private Column selectDimension(List<Column> columns, String requestedId) {
        List<Column> dimensions = columns.stream().filter(column -> column.role() == Role.DIMENSION).toList();
        if (dimensions.isEmpty()) {
            throw new IllegalArgumentException("查询结果没有可用于图表的维度");
        }
        if (blank(requestedId)) {
            return dimensions.get(0);
        }
        return dimensions.stream().filter(column -> requestedId.trim().equals(column.id())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("指定的图表维度不存在"));
    }

    private List<Column> selectMetrics(List<Column> columns, List<String> requestedIds) {
        List<Column> metrics = columns.stream().filter(column -> column.role() == Role.METRIC).toList();
        if (metrics.isEmpty()) {
            throw new IllegalArgumentException("查询结果没有可用于图表的度量");
        }
        if (requestedIds == null || requestedIds.isEmpty()) {
            return metrics;
        }
        Set<String> uniqueIds = new LinkedHashSet<>(requestedIds);
        List<Column> selected = new ArrayList<>();
        for (String id : uniqueIds) {
            metrics.stream().filter(column -> column.id().equals(id)).findFirst()
                    .ifPresentOrElse(selected::add, () -> {
                        throw new IllegalArgumentException("指定的图表度量不存在：" + id);
                    });
        }
        return List.copyOf(selected);
    }

    private BigDecimal number(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        try {
            return new BigDecimal(value.toString().trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("图表度量包含非数值数据");
        }
    }

    private String display(Object value) {
        return value == null ? "" : value.toString();
    }

    private String defaultTitle(List<Column> metrics, Column dimension) {
        return metrics.stream().map(Column::displayName).reduce((left, right) -> left + "、" + right)
                .orElse("度量") + "（按" + dimension.displayName() + "）";
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
