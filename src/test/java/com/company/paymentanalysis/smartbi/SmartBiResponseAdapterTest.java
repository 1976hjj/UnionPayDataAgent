package com.company.paymentanalysis.smartbi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.paymentanalysis.smartbi.SmartBiModels.QueryResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SmartBiResponseAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SmartBiResponseAdapter adapter = new SmartBiResponseAdapter(objectMapper);

    @Test
    void convertsOfficialDataIteratorResponseToLabeledRows() throws Exception {
        QueryResponse response = adapter.normalize(objectMapper.readTree("""
                {
                  "columnLabels": ["region", "amount"],
                  "iterator": [[
                    {"type":"STRING","displayValue":"East","value":"EAST"},
                    {"type":"BIGDECIMAL","displayValue":"52,640,000","value":52640000}
                  ]],
                  "totalRowCount": -1
                }
                """));

        assertThat(response.data()).hasSize(1);
        assertThat(response.data().get(0)).containsEntry("region", "EAST").containsEntry("amount", 52640000);
        assertThat(response.metadata()).containsEntry("source", "SmartBI DataIterator").containsEntry("totalRowCount", -1);
    }

    @Test
    void rejectsNonOfficialResponseShape() throws Exception {
        assertThatThrownBy(() -> adapter.normalize(objectMapper.readTree("{" + "\"data\":[]" + "}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DataIterator");
    }
}
