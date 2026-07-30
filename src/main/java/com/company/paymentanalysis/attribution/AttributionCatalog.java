package com.company.paymentanalysis.attribution;

import java.util.List;
import java.util.Map;

public final class AttributionCatalog {

    public static final Map<String, String> METRIC_NAMES = Map.of(
            "rmbAmount", "人民币总金额",
            "transactionCount", "交易笔数",
            "successRate", "支付成功率");

    public static final Map<String, String> DIMENSION_NAMES = Map.of(
            "acquiringRegion", "收单地区",
            "issuingRegion", "发卡地区",
            "acquiringInstitution", "收单机构",
            "transactionMedia", "交易介质");

    public static final Map<String, String> DIMENSION_FIELDS = Map.of(
            "acquiringRegion", "acq_mkt_ch",
            "issuingRegion", "iss_mkt_ch",
            "acquiringInstitution", "acq_ins_ch",
            "transactionMedia", "JYJZ_NAME");

    public static final Map<String, List<String>> DIMENSION_MEMBERS = Map.of(
            "acquiringRegion", List.of("华东地区", "华南地区", "华北地区", "西南地区"),
            "issuingRegion", List.of("境内发卡", "亚太地区", "欧洲地区", "北美地区"),
            "acquiringInstitution", List.of("收单机构A", "收单机构B", "收单机构C", "收单机构D"),
            "transactionMedia", List.of("芯片卡", "非接支付", "二维码", "磁条卡"));

    private AttributionCatalog() {
    }
}
