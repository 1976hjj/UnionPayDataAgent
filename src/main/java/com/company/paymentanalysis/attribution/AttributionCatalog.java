package com.company.paymentanalysis.attribution;

import com.company.paymentanalysis.query.QueryMetadataCatalog;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Fields that the attribution agent is allowed to use. */
public final class AttributionCatalog {

    private static final List<String> METRICS = List.of(
            "trans_cnt_m", "trans_amt_m", "trans_rmb_amt_m", "acpt_cnt_m",
            "acpt_trans_amt_m", "acpt_trans_rmb_amt_m", "sh_jy_num_m", "sh_cg_num_m");

    private static final Map<String, AttributionDimension> DIMENSIONS = dimensions(
            dimension("acq_ins_ch", "机构", "按收单机构识别业务承接方的变化"),
            dimension("iss_sc_ch", "地域", "按发卡市场识别交易来源地区"),
            dimension("acq_mkt_ch", "地域", "按收单市场识别受理地区"),
            dimension("ins_ins_ch", "机构", "按发卡机构识别卡片来源机构"),
            dimension("JYJZ_NAME", "介质", "芯片卡、非接、二维码等交易介质"),
            dimension("channel_def", "渠道", "POS、线上、移动端、ATM等交易渠道"),
            dimension("trans_nms", "交易", "消费、预授权、退货、取现等交易类型"),
            dimension("mpay_def", "支付方式", "移动支付产品及非移动支付"),
            dimension("brand", "卡片", "卡品牌"),
            dimension("card_attr_def", "卡片", "借记卡、贷记卡等卡性质"),
            dimension("card_media_def", "卡片", "实体卡、虚拟卡、Token卡等卡介质"),
            dimension("china_mcc_cde_lvl_3", "商户", "商户行业分类"),
            dimension("curr_nm_ch", "币种", "交易货币"),
            dimension("trans_scen_ind", "场景", "境内外、线上线下交易场景"),
            dimension("DEFINITION", "响应", "响应码对应的业务结果原因"));

    private AttributionCatalog() {
    }

    public static boolean isMetric(String metricId) {
        return METRICS.contains(metricId);
    }

    public static List<String> metricIds() {
        return METRICS;
    }

    public static String metricName(String metricId) {
        return QueryMetadataCatalog.displayName(metricId);
    }

    public static boolean isDimension(String dimensionId) {
        return DIMENSIONS.containsKey(dimensionId);
    }

    public static AttributionDimension dimension(String dimensionId) {
        AttributionDimension definition = DIMENSIONS.get(dimensionId);
        if (definition == null) {
            throw new IllegalArgumentException("不允许用于归因的维度：" + dimensionId);
        }
        return definition;
    }

    public static List<AttributionDimension> dimensions() {
        return List.copyOf(DIMENSIONS.values());
    }

    /** SmartBI owns these derived measures; Java only selects the matching field. */
    public static Optional<String> comparisonMetric(
            String metricId, YearMonth currentPeriod, YearMonth comparisonPeriod) {
        long months = ChronoUnit.MONTHS.between(comparisonPeriod, currentPeriod);
        String suffix = months == 1 ? "hb" : months == 12 ? "tb" : null;
        return suffix == null
                ? Optional.empty()
                : Optional.of(metricId.substring(0, metricId.length() - 1) + suffix);
    }

    private static AttributionDimension dimension(String id, String category, String description) {
        return new AttributionDimension(
                id, QueryMetadataCatalog.displayName(id), category, description, true);
    }

    private static Map<String, AttributionDimension> dimensions(AttributionDimension... definitions) {
        Map<String, AttributionDimension> result = new LinkedHashMap<>();
        for (AttributionDimension definition : definitions) {
            result.put(definition.id(), definition);
        }
        return Map.copyOf(result);
    }

    public record AttributionDimension(
            String id, String name, String category, String description, boolean attributionEnabled) {
    }
}
