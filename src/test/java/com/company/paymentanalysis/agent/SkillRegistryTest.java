package com.company.paymentanalysis.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.paymentanalysis.artifact.model.ArtifactType;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SkillRegistryTest {

    @Test
    void indexesSkillsByStableIdAndEntryModeAndPublishesSortedDescriptors() {
        AgentSkill query = skill("query", AgentEntryMode.BI_CHAT, ArtifactType.QUERY_RESULT);
        AgentSkill attribution = skill(
                "attribution", AgentEntryMode.ATTRIBUTION, ArtifactType.ATTRIBUTION_RESULT);

        SkillRegistry registry = new SkillRegistry(List.of(query, attribution));

        assertThat(registry.require(" QUERY ")).isSameAs(query);
        assertThat(registry.require(AgentEntryMode.ATTRIBUTION)).isSameAs(attribution);
        assertThat(registry.descriptors()).extracting(SkillDescriptor::skillId)
                .containsExactly("attribution", "query");
    }

    @Test
    void rejectsDuplicateIdsAndDuplicateEntryModesAtStartup() {
        assertThatThrownBy(() -> new SkillRegistry(List.of(
                skill("query", AgentEntryMode.BI_CHAT, ArtifactType.QUERY_RESULT),
                skill("query", AgentEntryMode.ATTRIBUTION, ArtifactType.ATTRIBUTION_RESULT))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Skill ID");

        assertThatThrownBy(() -> new SkillRegistry(List.of(
                skill("query", AgentEntryMode.BI_CHAT, ArtifactType.QUERY_RESULT),
                skill("another-query", AgentEntryMode.BI_CHAT, ArtifactType.QUERY_RESULT))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Skill 入口");
    }

    private AgentSkill skill(String id, AgentEntryMode entryMode, ArtifactType outputType) {
        SkillDescriptor descriptor = new SkillDescriptor(
                id, id, id + " description", entryMode, Set.of(AgentAction.MESSAGE),
                Set.of(), Set.of(outputType), false);
        return new AgentSkill() {
            @Override
            public SkillDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public AgentResponse execute(AgentRequest request, AgentContext context) {
                return null;
            }
        };
    }
}
