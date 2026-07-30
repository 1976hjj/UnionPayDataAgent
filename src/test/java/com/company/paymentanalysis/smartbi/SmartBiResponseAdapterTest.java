package com.company.paymentanalysis.smartbi;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.paymentanalysis.smartbi.SmartBiModels.QueryResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SmartBiResponseAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SmartBiResponseAdapter adapter = new SmartBiResponseAdapter(objectMapper);

    @Test
    void keepsNormalizedMockResponseCompatible() throws Exception {
        QueryResponse response = adapter.normalize(objectMapper.readTree("""
                {
                  "requestId": "mock-1",
                  "data": [{"memberName": "华东地区", "changeAmount": 12684320}],
                  "metadata": {"source": "Mock SmartBI route"}
                }
                """));

        assertThat(response.requestId()).isEqualTo("mock-1");
        assertThat(response.data().get(0).get("memberName")).isEqualTo("华东地区");
    }

    @Test
    void convertsOfficialDataIteratorResponseToLabeledRows() throws Exception {
        QueryResponse response = adapter.normalize(objectMapper.readTree("""
                {
                  "columnLabels": ["收单地区", "人民币总金额"],
                  "iterator": [
                    [
                      {"type": "STRING", "displayValue": "华东地区", "value": "华东地区"},
                      {"type": "BIGDECIMAL", "displayValue": "52,640,000", "value": 52640000}
                    ]
                  ],
                  "totalRowCount": -1
                }
                """));

        assertThat(response.data()).hasSize(1);
        assertThat(response.data().get(0))
                .containsEntry("收单地区", "华东地区")
                .containsEntry("人民币总金额", 52640000);
        assertThat(response.metadata())
                .containsEntry("source", "SmartBI DataIterator")
                .containsEntry("totalRowCount", -1);
    }
}
