package com.company.paymentanalysis.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.paymentanalysis.artifact.model.ArtifactType;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ToolRegistryTest {

    @Test
    void indexesToolsAndPublishesSortedDescriptors() {
        AgentTool xlsx = tool("export-xlsx", ArtifactType.FILE);
        AgentTool csv = tool("export-csv", ArtifactType.FILE);

        ToolRegistry registry = new ToolRegistry(List.of(xlsx, csv));

        assertThat(registry.require(" EXPORT-XLSX ")).isSameAs(xlsx);
        assertThat(registry.descriptors()).extracting(ToolDescriptor::toolId)
                .containsExactly("export-csv", "export-xlsx");
    }

    @Test
    void rejectsDuplicateToolIdsAtStartup() {
        assertThatThrownBy(() -> new ToolRegistry(List.of(
                tool("export-csv", ArtifactType.FILE),
                tool("export-csv", ArtifactType.FILE))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Tool ID");
    }

    private AgentTool tool(String id, ArtifactType outputType) {
        ToolDescriptor descriptor = new ToolDescriptor(
                id, id, id + " description", Set.of(ArtifactType.QUERY_RESULT),
                Set.of(outputType), ToolDescriptor.SideEffect.CREATES_ARTIFACT);
        return new AgentTool() {
            @Override
            public ToolDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public ToolExecutionResult execute(ToolExecutionRequest request) {
                return null;
            }
        };
    }
}
