package com.company.paymentanalysis.ragflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.paymentanalysis.ragflow.MetadataRetrievalTool.RetrievedMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** Verifies the development mock against the same three workbooks uploaded to RAGFlow. */
class RagflowMetadataToolWorkbookSmokeTest {

    private static final Path ROOT = Path.of("C:/Users/hjjgg/Desktop/agent/ragflow/ragflow修改后");

    @Test
    void separatesMetricDimensionAndValueCandidatesFromTheSourceWorkbooks() {
        Path metrics = ROOT.resolve("度量表.xlsx");
        Path dimensions = ROOT.resolve("维度表.xlsx");
        Path values = ROOT.resolve("维度值域.xlsx");
        Assumptions.assumeTrue(Files.isRegularFile(metrics)
                && Files.isRegularFile(dimensions) && Files.isRegularFile(values));

        RagflowMetadataTool tool = new RagflowMetadataTool(
                new RagflowProperties(false, true, "", "/api/v1/retrieval", "", List.of(),
                        new RagflowProperties.DocumentIds("", "", "", ""), 0.2, 0.3, "", 50, 1, 5,
                        new RagflowProperties.MockFiles(metrics.toString(), dimensions.toString(), values.toString())),
                RestClient.builder(), new ObjectMapper());

        RetrievedMetadata result = tool.retrieveForQuery("按卡品牌看乌拉圭的人民币总金额", """
                {"metricTerms":["人民币总金额"],"groupTerms":["卡品牌"],
                 "filterTerms":[{"dimensionTerm":"收单市场","operator":"EQUALS","values":["乌拉圭"],"context":"乌拉圭"}],
                 "sortTerms":[],"unmappedTerms":[]}
                """);

        assertThat(result.metrics()).extracting(candidate -> candidate.fieldId())
                .contains("trans_rmb_amt_m");
        assertThat(result.metrics().get(0).fieldId()).isEqualTo("trans_rmb_amt_m");
        assertThat(result.dimensions()).extracting(candidate -> candidate.fieldId()).contains("brand");
        assertThat(result.values()).extracting(candidate -> candidate.fieldId()).contains("acq_mkt_ch");
        assertThat(result.values()).extracting(candidate -> candidate.value()).contains("乌拉圭");
    }

    @Test
    void retrievesARealValueFromACompoundMetricPhrase() {
        Path metrics = ROOT.resolve("度量表.xlsx");
        Path dimensions = ROOT.resolve("维度表.xlsx");
        Path values = ROOT.resolve("维度值域.xlsx");
        Assumptions.assumeTrue(Files.isRegularFile(metrics)
                && Files.isRegularFile(dimensions) && Files.isRegularFile(values));

        RagflowMetadataTool tool = new RagflowMetadataTool(
                new RagflowProperties(false, true, "", "/api/v1/retrieval", "", List.of(),
                        new RagflowProperties.DocumentIds("", "", "", ""), 0.2, 0.3, "", 50, 1, 5,
                        new RagflowProperties.MockFiles(metrics.toString(), dimensions.toString(), values.toString())),
                RestClient.builder(), new ObjectMapper());

        RetrievedMetadata result = tool.retrieveForQuery("查VISA双标芯片卡的POS交易笔数和金额", """
                {"searchTerms":[
                   {"text":"VISA双标芯片卡","context":"查VISA双标芯片卡的POS交易笔数和金额"},
                   {"text":"POS交易笔数","context":"查VISA双标芯片卡的POS交易笔数和金额"}],
                 "metricTerms":["POS交易笔数","POS交易金额"],"groupTerms":[],
                 "filterTerms":[],"sortTerms":[],"unmappedTerms":[]}
                """);

        assertThat(result.values()).anySatisfy(candidate -> {
            assertThat(candidate.fieldId()).isEqualTo("trans_nms");
            assertThat(candidate.value()).isEqualTo("POS");
            assertThat(candidate.queryTerm()).isIn("POS交易笔数", "POS交易金额");
        });
        assertThat(result.values()).noneSatisfy(candidate -> {
            assertThat(candidate.fieldId()).isEqualTo("resp_cde");
            assertThat(candidate.value()).isEqualTo("SA");
        });
    }
}
