package com.company.paymentanalysis.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

class MockBusinessSemanticRetrievalToolTest {

    @Test
    void retrievesFullUtteranceAndExtractedTermsThenDeduplicatesByKnowledgeId() {
        MockBusinessSemanticRetrievalTool tool = tool();

        var result = tool.retrieve(
                "今年至今VCC（虚拟商务卡）总体业务情况",
                List.of("VCC", "虚拟商务卡", "总体业务情况"));

        assertThat(result.mocked()).isTrue();
        assertThat(result.candidates()).extracting(candidate -> candidate.rule().knowledgeId())
                .contains("semantic_vcc_filter_bundle_001", "semantic_business_overview_metrics_001")
                .doesNotHaveDuplicates();
        assertThat(result.candidates().stream()
                .filter(candidate -> candidate.rule().knowledgeId().equals("semantic_vcc_filter_bundle_001"))
                .findFirst().orElseThrow().matchedTerms())
                .contains("今年至今VCC（虚拟商务卡）总体业务情况", "VCC", "虚拟商务卡");
    }

    @Test
    void canRetrieveAHistoryTermEvenWhenCurrentMessageDoesNotContainIt() {
        var result = tool().retrieve("再加总交易笔数", List.of("VCC"));

        assertThat(result.candidates()).extracting(candidate -> candidate.rule().knowledgeId())
                .contains("semantic_vcc_filter_bundle_001");
    }

    private MockBusinessSemanticRetrievalTool tool() {
        return new MockBusinessSemanticRetrievalTool(
                new ObjectMapper(),
                new BusinessSemanticProperties(
                        true, "data/ragflow/business-semantic-rules.draft.jsonl", 0.90),
                new DefaultResourceLoader());
    }
}
