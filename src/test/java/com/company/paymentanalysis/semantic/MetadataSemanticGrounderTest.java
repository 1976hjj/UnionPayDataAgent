package com.company.paymentanalysis.semantic;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.paymentanalysis.ragflow.MetadataRetrievalTool.MetadataCandidate;
import com.company.paymentanalysis.ragflow.MetadataRetrievalTool.RetrievedMetadata;
import com.company.paymentanalysis.ragflow.MetadataRetrievalTool.Scope;
import java.util.List;
import org.junit.jupiter.api.Test;

class MetadataSemanticGrounderTest {

    @Test
    void separatesUniqueValueGroundingFromFieldAmbiguity() {
        RetrievedMetadata metadata = new RetrievedMetadata(
                List.of(),
                List.of(),
                List.of(
                        candidate("trans_nms", "交易类型", "POS", "POS交易笔数", 0.99),
                        candidate("acq_reg_ch", "收单分公司", "韩国", "韩国", 0.98),
                        candidate("iss_dq_ch", "发卡分公司", "韩国", "韩国", 0.97),
                        candidate("acq_reg_ch", "收单分公司", "南亚", "东南亚", 0.96),
                        candidate("sett_dt_Year2", "年", "2026", "今年", 1)),
                true);

        var result = MetadataSemanticGrounder.ground(QuerySemanticIntent.empty(), metadata);

        assertThat(result.requiredFilters()).singleElement().satisfies(filter -> {
            assertThat(filter.sourceTerm()).isEqualTo("POS交易笔数");
            assertThat(filter.matchedValue()).isEqualTo("POS");
            assertThat(filter.dimensionId()).isEqualTo("trans_nms");
        });
        assertThat(result.ambiguousFilters()).singleElement().satisfies(ambiguous -> {
            assertThat(ambiguous.sourceTerm()).isEqualTo("韩国");
            assertThat(ambiguous.matchedValue()).isEqualTo("韩国");
            assertThat(ambiguous.dimensionIds()).containsExactlyInAnyOrder("acq_reg_ch", "iss_dq_ch");
        });
    }

    @Test
    void groundsCanonicalValueOnlyWhenAnExplicitAliasFullyMatches() {
        MetadataCandidate alias = new MetadataCandidate(
                Scope.VALUE, "acq_mkt_ch", "收单市场", "东南亚",
                "地区; aliases=[\"东盟\"]", 0.99, "东盟", "mock");

        var result = MetadataSemanticGrounder.ground(
                QuerySemanticIntent.empty(),
                new RetrievedMetadata(List.of(), List.of(), List.of(alias), true));

        assertThat(result.requiredFilters()).singleElement().satisfies(filter -> {
            assertThat(filter.sourceTerm()).isEqualTo("东盟");
            assertThat(filter.matchedValue()).isEqualTo("东南亚");
            assertThat(filter.dimensionId()).isEqualTo("acq_mkt_ch");
        });
    }

    @Test
    void intersectsTheExplicitDimensionWithSharedValueOwners() {
        QuerySemanticIntent intent = new QuerySemanticIntent(
                List.of(),
                List.of("交易笔数"),
                List.of(),
                List.of(new QuerySemanticIntent.FilterTerm(
                        "有效标识", "EQUALS", List.of("1"), "有效标识为1")),
                List.of(),
                List.of());
        RetrievedMetadata metadata = new RetrievedMetadata(
                List.of(),
                List.of(candidate(
                        Scope.DIMENSION, "kpi_ind", "有效标识", "", "有效标识", 1)),
                List.of(
                        candidate("kpi_ind", "有效标识", "1", "1", 1),
                        candidate("mm_sh_sign", "免验密码标识", "1", "1", 1),
                        candidate("proc_ind", "处理标记", "1", "1", 1),
                        candidate("rev_ind", "冲正标记", "1", "1", 1),
                        candidate("srv_entry_mod", "服务点输入方式", "1", "1", 1)),
                true);

        var result = MetadataSemanticGrounder.ground(intent, metadata);

        assertThat(result.requiredFilters()).singleElement().satisfies(filter -> {
            assertThat(filter.sourceTerm()).isEqualTo("有效标识");
            assertThat(filter.matchedValue()).isEqualTo("1");
            assertThat(filter.dimensionId()).isEqualTo("kpi_ind");
        });
        assertThat(result.ambiguousFilters()).isEmpty();
    }

    private MetadataCandidate candidate(
            String fieldId, String fieldName, String value, String queryTerm, double score) {
        return candidate(Scope.VALUE, fieldId, fieldName, value, queryTerm, score);
    }

    private MetadataCandidate candidate(
            Scope scope, String fieldId, String fieldName, String value, String queryTerm, double score) {
        return new MetadataCandidate(
                scope, fieldId, fieldName, value, "test", score, queryTerm, "mock");
    }
}
