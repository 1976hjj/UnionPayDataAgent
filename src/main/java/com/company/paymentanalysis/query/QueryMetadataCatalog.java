package com.company.paymentanalysis.query;

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

public final class QueryMetadataCatalog {

    private static final Map<String, FieldDefinition> METRICS = orderedMap(
            new FieldDefinition("transactionAmount", "交易金额", "trans_amt", "trans_amt"),
            new FieldDefinition("transactionCount", "交易笔数", "trans_cnt", "trans_cnt"),
            new FieldDefinition("successRate", "支付成功率", "success_rate", "success_rate"));

    private static final Map<String, FieldDefinition> DIMENSIONS = orderedMap(
            new FieldDefinition("tradeYear", "年", "sett_dt_Year", "sett_dt_Year"),
            new FieldDefinition("tradeMonth", "月", "sett_dt_Month2", "sett_dt_Month2"),
            new FieldDefinition("tradeDate", "日", "sett_dt_Day", "trade_date"),
            new FieldDefinition("channel", "受理渠道", "accept_channel", "accept_channel"),
            new FieldDefinition("region", "地区", "region_name", "region_name"),
            new FieldDefinition("merchantType", "商户类型", "merchant_type", "merchant_type"),
            new FieldDefinition("paymentMethod", "支付方式", "payment_method", "payment_method"));

    private QueryMetadataCatalog() {
    }

    public static Set<String> metricIds() {
        return METRICS.keySet();
    }

    public static Set<String> dimensionIds() {
        return DIMENSIONS.keySet();
    }

    public static boolean isMetric(String id) {
        return METRICS.containsKey(id);
    }

    public static boolean isDimension(String id) {
        return DIMENSIONS.containsKey(id);
    }

    public static String displayName(String id) {
        FieldDefinition definition = METRICS.containsKey(id) ? METRICS.get(id) : DIMENSIONS.get(id);
        return definition == null ? id : definition.displayName();
    }

    public static String smartBiField(String id) {
        FieldDefinition definition = METRICS.containsKey(id) ? METRICS.get(id) : DIMENSIONS.get(id);
        if (definition == null) {
            throw new IllegalArgumentException("不支持的查询字段代码：" + id);
        }
        return definition.smartBiField();
    }

    public static String smartBiFilterField(String id) {
        FieldDefinition definition = DIMENSIONS.get(id);
        if (definition == null) {
            throw new IllegalArgumentException("不支持的过滤维度代码：" + id);
        }
        return definition.smartBiFilterField();
    }

    public static String displayNameBySmartBiField(String smartBiField) {
        return allFields().values().stream()
                .filter(field -> field.smartBiField().equals(smartBiField)
                        || field.smartBiFilterField().equals(smartBiField))
                .map(FieldDefinition::displayName)
                .findFirst()
                .orElse(smartBiField);
    }

    public static String metricPrompt() {
        return prompt(METRICS);
    }

    public static String dimensionPrompt() {
        return prompt(DIMENSIONS);
    }

    private static String prompt(Map<String, FieldDefinition> definitions) {
        return definitions.values().stream()
                .map(field -> field.id() + "=" + field.displayName())
                .reduce((left, right) -> left + "、" + right)
                .orElse("");
    }

    private static Map<String, FieldDefinition> orderedMap(FieldDefinition... definitions) {
        Map<String, FieldDefinition> result = new LinkedHashMap<>();
        for (FieldDefinition definition : definitions) {
            result.put(definition.id(), definition);
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, FieldDefinition> allFields() {
        Map<String, FieldDefinition> result = new LinkedHashMap<>(METRICS);
        result.putAll(DIMENSIONS);
        return result;
    }

    private record FieldDefinition(
            String id, String displayName, String smartBiField, String smartBiFilterField) {
    }
}
