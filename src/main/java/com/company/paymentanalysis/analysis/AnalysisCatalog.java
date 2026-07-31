package com.company.paymentanalysis.analysis;

import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AnalysisCatalog {

    private static final Map<String, String> METRICS = Map.ofEntries(
            Map.entry("rmbamount", "rmbAmount"),
            Map.entry("人民币总金额", "rmbAmount"),
            Map.entry("人民币金额", "rmbAmount"),
            Map.entry("交易金额", "rmbAmount"),
            Map.entry("金额", "rmbAmount"),
            Map.entry("transactionamount", "transactionAmount"),
            Map.entry("transactioncount", "transactionCount"),
            Map.entry("交易笔数", "transactionCount"),
            Map.entry("笔数", "transactionCount"),
            Map.entry("successrate", "successRate"),
            Map.entry("支付成功率", "successRate"),
            Map.entry("成功率", "successRate"));

    private static final Map<String, String> DIMENSIONS = Map.ofEntries(
            Map.entry("acquiringregion", "acquiringRegion"),
            Map.entry("收单地区", "acquiringRegion"),
            Map.entry("地区", "acquiringRegion"),
            Map.entry("国家", "acquiringRegion"),
            Map.entry("issuingregion", "issuingRegion"),
            Map.entry("发卡地区", "issuingRegion"),
            Map.entry("acquiringinstitution", "acquiringInstitution"),
            Map.entry("收单机构", "acquiringInstitution"),
            Map.entry("机构", "acquiringInstitution"),
            Map.entry("region", "region"),
            Map.entry("channel", "channel"),
            Map.entry("受理渠道", "channel"),
            Map.entry("渠道", "channel"),
            Map.entry("tradeyear", "tradeYear"),
            Map.entry("年", "tradeYear"),
            Map.entry("年份", "tradeYear"),
            Map.entry("trademonth", "tradeMonth"),
            Map.entry("月", "tradeMonth"),
            Map.entry("月份", "tradeMonth"),
            Map.entry("tradedate", "tradeDate"),
            Map.entry("日", "tradeDate"),
            Map.entry("日期", "tradeDate"),
            Map.entry("merchanttype", "merchantType"),
            Map.entry("商户类型", "merchantType"),
            Map.entry("paymentmethod", "paymentMethod"),
            Map.entry("支付方式", "paymentMethod"));

    public String resolveMetric(String text) {
        return resolve(text, METRICS);
    }

    public String resolveDimension(String text) {
        return resolve(text, DIMENSIONS);
    }

    public String resolveFilterField(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("time") || normalized.equals("period") || normalized.contains("时间")) {
            return "tradeDate";
        }
        String dimension = resolveDimension(text);
        return dimension.isBlank() ? text.trim() : dimension;
    }

    private String resolve(String value, Map<String, String> aliases) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        String exact = aliases.get(normalized);
        if (exact != null) {
            return exact;
        }
        return aliases.entrySet().stream()
                .filter(entry -> normalized.contains(entry.getKey()))
                .max(java.util.Comparator.comparingInt(entry -> entry.getKey().length()))
                .map(Map.Entry::getValue)
                .orElse("");
    }
}
