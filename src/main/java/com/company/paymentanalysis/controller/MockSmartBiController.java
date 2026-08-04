package com.company.paymentanalysis.controller;

import static com.company.paymentanalysis.attribution.AttributionCatalog.DIMENSION_FIELDS;
import static com.company.paymentanalysis.attribution.AttributionCatalog.DIMENSION_MEMBERS;

import com.company.paymentanalysis.smartbi.SmartBiModels.QueryRequest;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryResponse;
import com.company.paymentanalysis.smartbi.SmartBiModels.CellData;
import com.company.paymentanalysis.smartbi.SmartBiModels.DataIteratorResponse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
        String dimensionCode = DIMENSION_FIELDS.entrySet().stream()
                .filter(entry -> request.rows().contains(entry.getValue()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
        List<Map<String, Object>> data = dimensionCode == null
                ? List.of(Map.of("overallChangeRate", 12.8, "direction", "UP"))
                : memberRows(dimensionCode, request.columns().get(0));
        return new QueryResponse(
                "mock-smartbi-" + UUID.randomUUID(),
                data,
                Map.of(
                        "source", "Mock SmartBI route",
                        "periodsCombined", true,
                        "rowCount", data.size()));
    }

    private CellData cell(Object value) {
        String type = value == null ? "NULL"
                : value instanceof BigDecimal ? "BIGDECIMAL"
                : value instanceof Number ? "NUMBER"
                : value instanceof Boolean ? "BOOLEAN"
                : "STRING";
        return new CellData(type, value == null ? null : String.valueOf(value), value);
    }

    private List<Map<String, Object>> memberRows(String dimensionCode, String metricField) {
        List<String> members = DIMENSION_MEMBERS.get(dimensionCode);
        double[] current = {52_640_000, 31_280_000, 18_950_000, 12_420_000};
        double[] comparison = {46_130_000, 27_690_000, 20_080_000, 10_020_000};
        if (metricField.startsWith("acpt_trans_cnt")) {
            current = new double[] {526_400, 312_800, 189_500, 124_200};
            comparison = new double[] {461_300, 276_900, 200_800, 100_200};
        } else if (metricField.startsWith("pay_success_rate")) {
            current = new double[] {98.62, 97.84, 95.76, 96.38};
            comparison = new double[] {97.91, 97.20, 96.18, 95.42};
        }
        double[] contributions = {57.3, 31.6, -9.9, 21.0};
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int index = 0; index < members.size(); index++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("memberCode", dimensionCode + "-" + (index + 1));
            row.put("memberName", members.get(index));
            row.put("currentValue", current[index]);
            row.put("comparisonValue", comparison[index]);
            row.put("changeAmount", current[index] - comparison[index]);
            row.put("changeRate", (current[index] - comparison[index]) / comparison[index] * 100);
            row.put("contributionRate", contributions[index]);
            row.put("direction", current[index] >= comparison[index] ? "UP" : "DOWN");
            rows.add(row);
        }
        return rows;
    }
}
