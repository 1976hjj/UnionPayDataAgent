package com.company.paymentanalysis.agent.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.paymentanalysis.agent.AgentAction;
import com.company.paymentanalysis.agent.AgentContext;
import com.company.paymentanalysis.agent.AgentEntryMode;
import com.company.paymentanalysis.agent.AgentRequest;
import com.company.paymentanalysis.agent.AgentSkill;
import com.company.paymentanalysis.agent.SkillRegistry;
import com.company.paymentanalysis.agent.tool.AgentTool;
import com.company.paymentanalysis.agent.tool.ToolRegistry;
import com.company.paymentanalysis.artifact.model.Artifact;
import com.company.paymentanalysis.artifact.model.Artifact.Status;
import com.company.paymentanalysis.artifact.model.Artifact.StorageType;
import com.company.paymentanalysis.artifact.model.ArtifactType;
import com.company.paymentanalysis.artifact.service.ArtifactAccessService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeterministicSupervisorPlannerTest {

    private final SkillRegistry skills = mock(SkillRegistry.class);
    private final ToolRegistry tools = mock(ToolRegistry.class);
    private final ArtifactAccessService artifacts = mock(ArtifactAccessService.class);
    private final DeterministicSupervisorPlanner planner =
            new DeterministicSupervisorPlanner(skills, tools, artifacts);

    @BeforeEach
    void capabilitiesExist() {
        when(skills.require("query")).thenReturn(mock(AgentSkill.class));
        when(skills.require("attribution")).thenReturn(mock(AgentSkill.class));
        when(skills.require("visualization")).thenReturn(mock(AgentSkill.class));
        when(tools.require("export-data")).thenReturn(mock(AgentTool.class));
    }

    @Test
    void plansQueryAndKeepsAttributionAsAnExplicitBusinessMode() {
        AgentPlan query = planner.plan(request("查询本月交易", AgentEntryMode.BI_CHAT), context(AgentEntryMode.BI_CHAT));
        AgentPlan attribution = planner.plan(
                request("分析下降原因", AgentEntryMode.ATTRIBUTION), context(AgentEntryMode.ATTRIBUTION));

        assertThat(query.steps()).singleElement().satisfies(step -> {
            assertThat(step.capabilityType()).isEqualTo(AgentPlan.CapabilityType.SKILL);
            assertThat(step.capabilityId()).isEqualTo("query");
        });
        assertThat(attribution.steps()).singleElement()
                .extracting(AgentPlan.PlanStep::capabilityId).isEqualTo("attribution");
    }

    @Test
    void plansNaturalLanguageVisualizationAndExportFromTheLatestQueryArtifact() {
        when(artifacts.findByConversation("user", "conversation"))
                .thenReturn(List.of(view("art_query", ArtifactType.QUERY_RESULT)));

        AgentPlan chart = planner.plan(
                request("把结果生成柱状图", AgentEntryMode.BI_CHAT), context(AgentEntryMode.BI_CHAT));
        AgentPlan export = planner.plan(
                request("下载 CSV 原始数据", AgentEntryMode.BI_CHAT), context(AgentEntryMode.BI_CHAT));

        assertThat(chart.steps()).singleElement().satisfies(step -> {
            assertThat(step.capabilityId()).isEqualTo("visualization");
            assertThat(step.inputArtifactIds()).containsExactly("art_query");
            assertThat(step.arguments()).containsEntry("chartType", "BAR");
        });
        assertThat(export.steps()).singleElement().satisfies(step -> {
            assertThat(step.capabilityType()).isEqualTo(AgentPlan.CapabilityType.TOOL);
            assertThat(step.capabilityId()).isEqualTo("export-data");
            assertThat(step.arguments()).containsEntry("format", "CSV");
        });
    }

    @Test
    void asksForAQueryBeforePlanningArtifactDependentCapabilities() {
        when(artifacts.findByConversation("user", "conversation")).thenReturn(List.of());

        AgentPlan plan = planner.plan(
                request("给我导出 Excel", AgentEntryMode.BI_CHAT), context(AgentEntryMode.BI_CHAT));

        assertThat(plan.status()).isEqualTo(AgentPlan.Status.NEEDS_INPUT);
        assertThat(plan.steps()).isEmpty();
        assertThat(plan.message()).contains("先完成查询");
    }

    @Test
    void treatsColloquialGiveMeAChartAsVisualizationInsteadOfAQuery() {
        when(artifacts.findByConversation("user", "conversation")).thenReturn(List.of());

        AgentPlan plan = planner.plan(
                request("给我按月的图吧", AgentEntryMode.BI_CHAT), context(AgentEntryMode.BI_CHAT));

        assertThat(plan.status()).isEqualTo(AgentPlan.Status.NEEDS_INPUT);
        assertThat(plan.steps()).isEmpty();
        assertThat(plan.message()).contains("先完成");
    }

    private AgentRequest request(String message, AgentEntryMode mode) {
        return new AgentRequest(
                "user", "conversation", message, mode, "model", false,
                null, null, null, null, List.of(), Map.of(), AgentAction.MESSAGE);
    }

    private AgentContext context(AgentEntryMode mode) {
        return new AgentContext(
                "user", "conversation", mode, "model", false,
                null, null, null, AgentAction.MESSAGE);
    }

    private ArtifactAccessService.ArtifactView view(String id, ArtifactType type) {
        return new ArtifactAccessService.ArtifactView(
                id, type, 1, Status.COMPLETED, "title", "user", "conversation", null,
                "test", StorageType.INLINE, 1L, Instant.now(), null, null);
    }
}
