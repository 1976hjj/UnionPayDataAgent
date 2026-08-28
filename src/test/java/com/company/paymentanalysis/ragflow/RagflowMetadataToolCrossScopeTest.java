package com.company.paymentanalysis.ragflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.paymentanalysis.ragflow.RagflowMetadataTool.RetrievalPlan;
import com.company.paymentanalysis.ragflow.MetadataRetrievalTool.MetadataCandidate;
import com.company.paymentanalysis.ragflow.MetadataRetrievalTool.Scope;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class RagflowMetadataToolCrossScopeTest {

    @Test
    void keepsTopCandidatesForEveryQueryTermInsteadOfUsingOneGlobalTopN() {
        List<MetadataCandidate> retained = RagflowMetadataTool.retainPerQueryTerm(List.of(
                value("acq_reg_ch", "东南亚", 1, "东南亚"),
                value("iss_dq_ch", "东南亚", 0.99, "东南亚"),
                value("trans_nms", "POS", 0.98, "POS笔数"),
                value("channel_def", "POS渠道", 0.90, "POS笔数")), 1);

        assertThat(retained).extracting(MetadataCandidate::queryTerm)
                .containsExactly("东南亚", "POS笔数");
        assertThat(retained).anySatisfy(candidate -> {
            assertThat(candidate.fieldId()).isEqualTo("trans_nms");
            assertThat(candidate.value()).isEqualTo("POS");
        });
    }

    @Test
    void usesSlotsAsPrimaryRoutesWithoutMakingThemRetrievalBoundaries() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        RagflowMetadataTool tool = new RagflowMetadataTool(
                new RagflowProperties(false, true, "", "/api/v1/retrieval", "", List.of(),
                        new RagflowProperties.DocumentIds("", "", "", ""), 0.2, 0.3, "", 50, 1, 5,
                        new RagflowProperties.MockFiles("", "", "")),
                RestClient.builder(), objectMapper);

        RetrievalPlan plan = tool.queryPlan(objectMapper.readTree("""
                {"searchTerms":[{"text":"POS交易笔数","context":"查POS交易笔数和金额"}],
                 "metricTerms":["POS交易笔数","POS交易金额"],"groupTerms":[],
                 "filterTerms":[{"dimensionTerm":"年","operator":"EQUALS","values":["2025"],"context":"去年"}],
                 "sortTerms":[{"fieldTerm":"交易金额","direction":"DESC"}],
                 "unmappedTerms":["双标芯片卡"]}
                """));

        assertThat(plan.metrics())
                .containsExactly("POS交易笔数", "POS交易金额", "交易金额", "双标芯片卡");
        assertThat(plan.dimensions())
                .containsExactly("年", "交易金额", "POS交易笔数", "双标芯片卡");
        assertThat(plan.filters())
                .containsExactly("POS交易笔数", "双标芯片卡", "POS交易金额");
    }

    @Test
    void routesTimeFiltersToTimeDimensionsWithoutSearchingOrdinaryValues() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        RagflowMetadataTool tool = new RagflowMetadataTool(
                new RagflowProperties(false, true, "", "/api/v1/retrieval", "", List.of(),
                        new RagflowProperties.DocumentIds("", "", "", ""), 0.2, 0.3, "", 50, 1, 5,
                        new RagflowProperties.MockFiles("", "", "")),
                RestClient.builder(), objectMapper);

        RetrievalPlan plan = tool.queryPlan(objectMapper.readTree("""
                {"searchTerms":[],"metricTerms":["交易笔数"],"groupTerms":[],
                 "filterTerms":[{"dimensionTerm":"日","operator":"BETWEEN",
                   "values":["2025-09-11","2025-10-23"],"context":"在指定日期期间"}],
                 "sortTerms":[],"unmappedTerms":[]}
                """));

        assertThat(plan.dimensions()).containsExactly("日");
        assertThat(plan.filters()).containsExactly("交易笔数");
        assertThat(plan.filters()).doesNotContain(
                "2025-09-11", "日 2025-09-11", "2025-10-23", "日 2025-10-23");
    }

    @Test
    void keepsAnExplicitNumericCodeAsAStandaloneValueLookup() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        RagflowMetadataTool tool = new RagflowMetadataTool(
                new RagflowProperties(false, true, "", "/api/v1/retrieval", "", List.of(),
                        new RagflowProperties.DocumentIds("", "", "", ""), 0.2, 0.3, "", 50, 1, 5,
                        new RagflowProperties.MockFiles("", "", "")),
                RestClient.builder(), objectMapper);

        RetrievalPlan plan = tool.queryPlan(objectMapper.readTree("""
                {"searchTerms":[],"metricTerms":[],"groupTerms":[],
                 "filterTerms":[{"dimensionTerm":"交易渠道代码","operator":"EQUALS",
                   "values":["11"],"context":"交易渠道代码为11"}],
                 "sortTerms":[],"unmappedTerms":[]}
                """));

        assertThat(plan.filters()).containsExactly("11", "交易渠道代码 11");
        assertThat(ValueCandidateMatcher.matches(plan.filters().get(0), "11")).isTrue();
        assertThat(ValueCandidateMatcher.matches(plan.filters().get(1), "11")).isFalse();
    }

    @Test
    void skipsValuesGeneratedByAResolvedBusinessRule() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        RagflowMetadataTool tool = new RagflowMetadataTool(
                new RagflowProperties(false, true, "", "/api/v1/retrieval", "", List.of(),
                        new RagflowProperties.DocumentIds("", "", "", ""), 0.2, 0.3, "", 50, 1, 5,
                        new RagflowProperties.MockFiles("", "", "")),
                RestClient.builder(), objectMapper);

        RetrievalPlan plan = tool.queryPlan(objectMapper.readTree("""
                {"searchTerms":[],"metricTerms":[],"groupTerms":[],
                 "filterTerms":[
                   {"dimensionTerm":"收单市场","operator":"IN",
                    "values":["日本","韩国"],"context":"业务语义规则标准化"},
                   {"dimensionTerm":"交易类型","operator":"EQUALS",
                    "values":["POS"],"context":"用户明确指定POS"}],
                 "sortTerms":[],"unmappedTerms":[]}
                """));

        assertThat(plan.dimensions()).containsExactly("收单市场", "交易类型");
        assertThat(plan.filters()).containsExactly("POS", "交易类型 POS");
        assertThat(plan.filters()).doesNotContain("日本", "收单市场 日本", "韩国", "收单市场 韩国");
    }

    private MetadataCandidate value(
            String fieldId, String value, double score, String queryTerm) {
        return new MetadataCandidate(
                Scope.VALUE, fieldId, fieldId, value, "test", score, queryTerm, "mock");
    }
}
