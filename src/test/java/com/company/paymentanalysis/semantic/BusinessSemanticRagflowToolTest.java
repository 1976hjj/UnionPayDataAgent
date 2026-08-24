package com.company.paymentanalysis.semantic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.ExpectedCount.twice;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.company.paymentanalysis.ragflow.RagflowProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class BusinessSemanticRagflowToolTest {

    @Test
    void remoteModeUsesSharedConnectionRetrievesTopFiveAndDeduplicatesByKnowledgeId() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        BusinessSemanticProperties semanticProperties = new BusinessSemanticProperties(true, "missing.jsonl", 0.90);
        RagflowProperties ragflowProperties = new RagflowProperties(
                true, false, "https://retrieval.company.test", "/api/v1/retrieval", "secret",
                List.of("dataset-1"),
                new RagflowProperties.DocumentIds("metric-doc", "dimension-doc", "value-doc", "semantic-doc"),
                0.2, 0.3, "", 50, 1, 5, new RagflowProperties.MockFiles("", "", ""));
        MockBusinessSemanticRetrievalTool local = new MockBusinessSemanticRetrievalTool(
                objectMapper, semanticProperties, new DefaultResourceLoader());
        BusinessSemanticRagflowTool tool = new BusinessSemanticRagflowTool(
                ragflowProperties, semanticProperties, local, builder, objectMapper);

        String rule = objectMapper.writeValueAsString(objectMapper.readTree(
                """
                {"knowledgeId":"semantic_vcc_filter_bundle_001","term":"VCC","aliases":["虚拟商务卡"],
                 "conceptType":"FILTER_BUNDLE","matchPolicy":{"type":"ANY_ALIAS","caseSensitive":false},
                 "semanticRewrite":{"relation":"AND","filterTerms":[
                   {"dimensionTerm":"B2B产品标识","operator":"BETWEEN","values":["10","20"]},
                   {"dimensionTerm":"发卡机构代码","operator":"IN","values":["22090702","47300702"]}]},
                 "targetConstraint":{"relation":"AND","metricIds":[],"dimensionFilters":[
                   {"dimensionId":"bi_tag","operator":"BETWEEN","values":["10","20"]},
                   {"dimensionId":"iss_ins_cde","operator":"IN","values":["22090702","47300702"]}]},
                 "requiresConfirmation":true,"confidence":"HIGH","enabled":true,"version":"1.0"}
                """));
        String response = objectMapper.writeValueAsString(objectMapper.createObjectNode()
                .put("code", 0)
                .set("data", objectMapper.createObjectNode().set("chunks", objectMapper.createArrayNode()
                        .add(objectMapper.createObjectNode().put("content", rule).put("similarity", 0.98)))));
        server.expect(twice(), requestTo("https://retrieval.company.test/api/v1/retrieval"))
                .andExpect(method(POST))
                .andExpect(header("Authorization", "Bearer secret"))
                .andExpect(jsonPath("$.dataset_ids[0]").value("dataset-1"))
                .andExpect(jsonPath("$.document_ids[0]").value("semantic-doc"))
                .andExpect(jsonPath("$.page_size").value(5))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        var result = tool.retrieve("今年VCC业务", List.of("VCC"));

        assertThat(result.mocked()).isFalse();
        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).matchedTerms()).containsExactly("今年VCC业务", "VCC");
        assertThat(result.candidates().get(0).rule().knowledgeId())
                .isEqualTo("semantic_vcc_filter_bundle_001");
        server.verify();
    }
}
