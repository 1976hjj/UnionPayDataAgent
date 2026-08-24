package com.company.paymentanalysis.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.paymentanalysis.semantic.QuerySemanticIntent.FilterTerm;
import com.company.paymentanalysis.semantic.QuerySemanticIntent.SearchTerm;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

class BusinessSemanticNormalizerTest {
    @Test
    void expandsVccAndBusinessOverviewWithoutLeavingBlackTermsUnresolved() {
        QuerySemanticIntent source = new QuerySemanticIntent(
                List.of(
                        new SearchTerm("VCC", "VCC（虚拟商务卡）"),
                        new SearchTerm("虚拟商务卡", "VCC（虚拟商务卡）"),
                        new SearchTerm("总体业务情况", "总体业务情况")),
                List.of("总体业务情况"),
                List.of(),
                List.of(
                        new FilterTerm("年", "EQUALS", List.of("2026"), "今年至今VCC（虚拟商务卡）总体业务情况"),
                        new FilterTerm("VCC（虚拟商务卡）", "EQUALS", List.of("VCC（虚拟商务卡）"), "VCC（虚拟商务卡）")),
                List.of(),
                List.of("VCC", "总体业务情况"));
        MockBusinessSemanticRetrievalTool retrieval = tool();

        var normalized = new BusinessSemanticNormalizer().normalize(
                source, retrieval.retrieve("今年至今VCC（虚拟商务卡）总体业务情况", source.retrievalTerms()));

        assertThat(normalized.intent().metricTerms())
                .containsExactly("总交易笔数", "承兑笔数", "人民币承兑金额");
        assertThat(normalized.intent().filterTerms()).extracting(FilterTerm::dimensionTerm)
                .containsExactly("年", "B2B产品标识", "发卡机构代码");
        assertThat(normalized.intent().unmappedTerms()).isEmpty();
        assertThat(normalized.enforcedMetricIds())
                .containsExactly("trans_cnt_m", "acpt_cnt_m", "acpt_trans_rmb_amt_m");
        assertThat(normalized.enforcedFilters()).satisfiesExactly(
                filter -> {
                    assertThat(filter.dimensionId()).isEqualTo("bi_tag");
                    assertThat(filter.operator()).isEqualTo("BETWEEN");
                    assertThat(filter.values()).containsExactly("10", "20");
                },
                filter -> {
                    assertThat(filter.dimensionId()).isEqualTo("iss_ins_cde");
                    assertThat(filter.operator()).isEqualTo("IN");
                    assertThat(filter.values()).containsExactly("22090702", "47300702");
                });
    }

    @Test
    void consumesFragmentsCoveredByAnAppliedFilterBundle() {
        QuerySemanticIntent source = new QuerySemanticIntent(
                List.of(new SearchTerm("中国大陆至15市场", "中国大陆至15市场的交易")),
                List.of("交易笔数"), List.of(), List.of(), List.of(), List.of("15市场"));
        MockBusinessSemanticRetrievalTool retrieval = tool();

        var normalized = new BusinessSemanticNormalizer().normalize(
                source, retrieval.retrieve("中国大陆至15市场的交易", source.retrievalTerms()));

        assertThat(normalized.intent().unmappedTerms()).isEmpty();
        assertThat(normalized.consumedTerms()).contains("15市场");
        assertThat(normalized.intent().filterTerms()).hasSize(2).allSatisfy(filter ->
                assertThat(filter.context()).isEqualTo(BusinessSemanticNormalizer.RULE_GENERATED_CONTEXT));
        assertThat(normalized.enforcedFilters()).satisfiesExactly(
                filter -> assertThat(filter.dimensionId()).isEqualTo("iss_sc_ch"),
                filter -> assertThat(filter.dimensionId()).isEqualTo("acq_mkt_ch"));
    }

    private MockBusinessSemanticRetrievalTool tool() {
        return new MockBusinessSemanticRetrievalTool(
                new ObjectMapper(),
                new BusinessSemanticProperties(
                        true, "data/ragflow/business-semantic-rules.draft.jsonl", 0.90),
                new DefaultResourceLoader());
    }
}
