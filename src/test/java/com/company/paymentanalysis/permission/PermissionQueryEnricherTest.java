package com.company.paymentanalysis.permission;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.paymentanalysis.smartbi.SmartBiModels.Filter;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryRequest;
import com.company.paymentanalysis.smartbi.SmartBiModels.RelationNode;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class PermissionQueryEnricherTest {

    @Test
    void wrapsTheBusinessTreeAndAddsMandatoryPermissionLeaves() {
        Filter businessFilter = new Filter("1", "brand", "IN", List.of("UnionPay", "Visa"));
        QueryRequest source = new QueryRequest(
                "dataset", List.of("brand"), List.of("trans_cnt_m"), List.of(businessFilter),
                RelationNode.group("OR", List.of(RelationNode.leaf(businessFilter))));
        LinkedHashMap<String, List<String>> values = new LinkedHashMap<>();
        values.put("acq_reg_ch", List.of("中国大陆"));
        values.put("iss_dq_ch", List.of("中国大陆"));

        QueryRequest result = new PermissionQueryEnricher().apply(
                source, new PermissionScope("demo-user", values));

        assertThat(result.filters()).hasSize(3);
        assertThat(result.filters()).extracting(Filter::name)
                .containsExactly("brand", "acq_reg_ch", "iss_dq_ch");
        assertThat(result.filters()).extracting(Filter::operation)
                .containsExactly("IN", "EQUALS", "EQUALS");
        assertThat(result.relationNode().relation()).isEqualTo("AND");
        assertThat(result.relationNode().childNodes()).hasSize(3);
        assertThat(result.relationNode().childNodes().get(0)).isEqualTo(source.relationNode());
        assertThat(result.relationNode().childNodes().subList(1, 3)).allMatch(RelationNode::leaf);
    }
}
