package com.company.paymentanalysis.normalize;

import com.company.paymentanalysis.analysis.QueryPlan;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryResponse;
import com.company.paymentanalysis.smartbi.SmartBiQueryBuilder;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class DefaultSmartBiResultNormalizer implements SmartBiResultNormalizer {

    private final SmartBiQueryBuilder queryBuilder;

    public DefaultSmartBiResultNormalizer(SmartBiQueryBuilder queryBuilder) {
        this.queryBuilder = queryBuilder;
    }

    @Override
    public List<NormalizedDataRow> normalize(QueryResponse response, QueryPlan queryPlan) {
        if (response == null) {
            throw new IllegalArgumentException("SmartBI 响应不能为空");
        }
        if (queryPlan == null) {
            throw new IllegalArgumentException("QueryPlan 不能为空");
        }

        String metricField = queryBuilder.metricField(queryPlan.metricCode());
        List<String> dimensionFields = queryPlan.dimensionCodes().stream()
                .map(queryBuilder::dimensionField)
                .toList();
        List<Map<String, Object>> sourceRows =
                response.data() == null ? List.of() : response.data();
        List<NormalizedDataRow> normalizedRows = new ArrayList<>(sourceRows.size());

        for (int index = 0; index < sourceRows.size(); index++) {
            Map<String, Object> source = sourceRows.get(index);
            requireField(source, metricField, index);

            Map<String, Object> dimensions = new LinkedHashMap<>();
            for (int dimensionIndex = 0;
                    dimensionIndex < queryPlan.dimensionCodes().size();
                    dimensionIndex++) {
                String dimensionCode = queryPlan.dimensionCodes().get(dimensionIndex);
                String dimensionField = dimensionFields.get(dimensionIndex);
                requireField(source, dimensionField, index);
                dimensions.put(dimensionCode, source.get(dimensionField));
            }

            Map<String, BigDecimal> metrics = new LinkedHashMap<>();
            metrics.put(
                    queryPlan.metricCode(),
                    toBigDecimal(source.get(metricField), metricField, index));
            normalizedRows.add(new NormalizedDataRow(dimensions, metrics, index));
        }
        return List.copyOf(normalizedRows);
    }

    private void requireField(Map<String, Object> row, String field, int rowIndex) {
        if (row == null || !row.containsKey(field)) {
            throw new IllegalArgumentException(
                    "SmartBI 第 " + rowIndex + " 行缺少字段：" + field);
        }
    }

    private BigDecimal toBigDecimal(Object value, String field, int rowIndex) {
        if (value == null || value instanceof String text && text.isBlank()) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number || value instanceof String) {
            try {
                String normalized = value.toString()
                        .trim()
                        .replace(",", "")
                        .replace("¥", "")
                        .replace("￥", "");
                return new BigDecimal(normalized);
            } catch (NumberFormatException exception) {
                throw invalidNumber(field, rowIndex, value, exception);
            }
        }
        throw invalidNumber(field, rowIndex, value, null);
    }

    private IllegalArgumentException invalidNumber(
            String field, int rowIndex, Object value, Exception cause) {
        String message =
                "SmartBI 第 " + rowIndex + " 行字段 " + field + " 不是合法数字：" + value;
        return cause == null
                ? new IllegalArgumentException(message)
                : new IllegalArgumentException(message, cause);
    }
}
