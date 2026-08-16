package com.company.paymentanalysis.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.paymentanalysis.chat.ConversationArtifact.VerifiedFact;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConversationRouterServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConversationRouterService router =
            new ConversationRouterService(null, null, null, objectMapper);

    @Test
    void readsLegacyArtifactWithoutPromotingNarrativeToVerifiedFacts() throws Exception {
        ConversationArtifact artifact = objectMapper.readValue("""
                {"id":"legacy-1","type":"ATTRIBUTION","title":"旧归因","createdAt":"2026-08-01T00:00:00Z",
                 "summary":"模型说机构A是原因","requestContract":"周期=2026-07 对比 2026-06",
                 "evidence":"旧版混合内容"}
                """, ConversationArtifact.class);

        assertThat(artifact.verifiedFacts()).isEmpty();
        assertThat(artifact.modelNarrative()).isEqualTo("模型说机构A是原因");
    }

    @Test
    void selectsAnOlderArtifactByMetricAndCompletePeriodPair() {
        ConversationArtifact older = artifact(
                "attr-old", "交易笔数", "2026-06", "2026-05");
        ConversationArtifact latest = artifact(
                "attr-latest", "人民币总金额", "2026-07", "2026-06");

        var selection = router.selectArtifacts(
                "把交易笔数 2026-06 对比 2026-05 的归因写成汇报", List.of(older, latest));

        assertThat(selection.ambiguous()).isFalse();
        assertThat(selection.selected()).extracting(ConversationArtifact::id).containsExactly("attr-old");
    }

    @Test
    void asksForClarificationInsteadOfMixingArtifactsWithTheSamePeriod() {
        ConversationArtifact amount = artifact(
                "attr-amount", "人民币总金额", "2026-07", "2026-06");
        ConversationArtifact count = artifact(
                "attr-count", "总交易笔数", "2026-07", "2026-06");

        var selection = router.selectArtifacts(
                "解释 2026-07 对比 2026-06 的归因", List.of(amount, count));

        assertThat(selection.ambiguous()).isTrue();
        assertThat(selection.selected()).isEmpty();
        assertThat(selection.candidates()).hasSize(2);
    }

    @Test
    void defaultsAnUnqualifiedFollowUpToOnlyTheLatestArtifact() {
        ConversationArtifact older = artifact(
                "attr-old", "交易笔数", "2026-06", "2026-05");
        ConversationArtifact latest = artifact(
                "attr-latest", "人民币总金额", "2026-07", "2026-06");

        var selection = router.selectArtifacts("把刚才结果写成邮件", List.of(older, latest));

        assertThat(selection.selected()).extracting(ConversationArtifact::id).containsExactly("attr-latest");
    }

    private ConversationArtifact artifact(
            String id, String metricName, String currentPeriod, String comparisonPeriod) {
        return new ConversationArtifact(
                id, "ATTRIBUTION",
                metricName + "归因（" + currentPeriod + " 对比 " + comparisonPeriod + "）",
                "2026-08-15T00:00:00Z", "旧摘要",
                "指标=" + metricName + "；周期=" + currentPeriod + " 对比 " + comparisonPeriod,
                "旧字段", Map.of(
                        "metricId", id,
                        "metricName", metricName,
                        "currentPeriod", currentPeriod,
                        "comparisonPeriod", comparisonPeriod),
                List.of(new VerifiedFact("overall.direction", "变化方向", "DOWN", "JAVA_OVERALL")),
                "模型叙述");
    }
}
