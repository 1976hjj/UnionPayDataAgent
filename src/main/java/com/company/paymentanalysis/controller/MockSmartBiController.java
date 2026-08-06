package com.company.paymentanalysis.controller;

import com.company.paymentanalysis.smartbi.SmartBiModels.QueryRequest;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryResponse;
import com.company.paymentanalysis.smartbi.SmartBiModels.CellData;
import com.company.paymentanalysis.smartbi.SmartBiModels.DataIteratorResponse;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mock/smartbi")
public class MockSmartBiController {

    private final MockChatSmartBiDataService chatDataService;

    public MockSmartBiController(MockChatSmartBiDataService chatDataService) {
        this.chatDataService = chatDataService;
    }

    @PostMapping("/query")
    public DataIteratorResponse query(@RequestBody QueryRequest request) {
        QueryResponse response = queryData(request);
        List<String> labels = response.data().isEmpty()
                ? List.of()
                : List.copyOf(response.data().get(0).keySet());
        List<List<CellData>> iterator = response.data().stream()
                .map(row -> labels.stream().map(label -> cell(row.get(label))).toList())
                .toList();
        return new DataIteratorResponse(labels, iterator, -1);
    }

    private QueryResponse queryData(QueryRequest request) {
        if (chatDataService.supports(request)) {
            return chatDataService.query(request);
        }
        throw new IllegalArgumentException("Mock SmartBI 不支持请求中的字段");
    }

    private CellData cell(Object value) {
        String type = value == null ? "NULL"
                : value instanceof BigDecimal ? "BIGDECIMAL"
                : value instanceof Number ? "NUMBER"
                : value instanceof Boolean ? "BOOLEAN"
                : "STRING";
        return new CellData(type, value == null ? null : String.valueOf(value), value);
    }

}
