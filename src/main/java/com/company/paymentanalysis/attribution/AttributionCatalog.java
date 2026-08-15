package com.company.paymentanalysis.attribution;

import com.company.paymentanalysis.query.QueryMetadataCatalog;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Fields that the attribution agent is allowed to use. */
public final class AttributionCatalog {

    private static final List<String> METRICS = List.of(
            "trans_cnt_m", "trans_amt_m", "trans_rmb_amt_m", "acpt_cnt_m",
            "acpt_trans_amt_m", "acpt_trans_rmb_amt_m", "sh_jy_num_m", "sh_cg_num_m",
            "trans_cnt_tb", "trans_cnt_hb", "trans_amt_tb", "trans_amt_hb",
            "trans_rmb_amt_tb", "trans_rmb_amt_hb", "acpt_cnt_tb", "acpt_cnt_hb",
            "acpt_trans_amt_tb", "acpt_trans_amt_hb", "acpt_trans_rmb_amt_tb", "acpt_trans_rmb_amt_hb",
            "sh_jy_num_tb", "sh_jy_num_hb", "sh_cg_num_tb", "sh_cg_num_hb");

    private static final Map<String, AttributionDimension> DIMENSIONS = dimensions(
            dimension("sett_dt_Year2", "统计周期", "按年分析"),
            dimension("sett_dt_Month2", "统计周期", "按月分析"),
            dimension("sett_dt_Day2", "统计周期", "按日分析"),
            dimension("acq_reg_ch", "地区", "按收单分公司分析"),
            dimension("acq_mkt_ch", "地区", "按收单市场分析"),
            dimension("acq_reg_cde", "地区", "按收单市场代码分析"),
            dimension("iss_dq_ch", "地区", "按发卡分公司分析"),
            dimension("iss_sc_ch", "地区", "按发卡市场分析"),
            dimension("iss_reg_cde", "地区", "按发卡市场代码分析"),
            dimension("reg_nm_lvl_1", "地区", "按中国大陆受理省市分析"),
            dimension("reg_nm_lvl_2", "地区", "按中国大陆受理区县分析"),
            dimension("acq_ins_cde", "机构", "按收单机构代码分析"),
            dimension("iss_ins_cde", "机构", "按发卡机构代码分析"),
            dimension("acq_ins_ch", "机构", "按收单机构名称分析"),
            dimension("ins_ins_ch", "机构", "按发卡机构名称分析"),
            dimension("fwd_ins_cde", "机构", "按转发机构代码分析"),
            dimension("recv_ins_cde", "机构", "按接受机构代码分析"),
            dimension("recv_ins_nm", "机构", "按接受机构名称分析"),
            dimension("eci", "交易参数", "按电子商务标识分析"),
            dimension("mm_sh_sign", "交易参数", "按免验密码标识分析"),
            dimension("trans_nms", "交易参数", "按交易类型分析"),
            dimension("acq_pos_cond_cde", "交易参数", "按服务点条件码分析"),
            dimension("instalment", "交易参数", "按分期期数分析"),
            dimension("mpay_def", "交易参数", "按移动支付分析"),
            dimension("resp_cde", "交易参数", "按响应码分析"),
            dimension("DEFINITION", "交易参数", "按响应码名称分析"),
            dimension("proc_ind", "交易参数", "按处理标记分析"),
            dimension("rev_ind", "交易参数", "按冲正标记分析"),
            dimension("trans_cde", "交易参数", "按交易代码分析"),
            dimension("trans_nm", "交易参数", "按交易代码名称分析"),
            dimension("trans_mod_def", "交易参数", "按交易模式名称分析"),
            dimension("channel", "交易参数", "按交易渠道代码分析"),
            dimension("channel_def", "交易参数", "按交易渠道名称分析"),
            dimension("curr_cde", "交易参数", "按交易货币代码分析"),
            dimension("curr_nm_ch", "交易参数", "按交易货币名称分析"),
            dimension("srv_entry_mod", "交易参数", "按服务点输入方式分析"),
            dimension("ic_cond_cde", "交易参数", "按IC卡条件代码分析"),
            dimension("JYJZ_NAME", "交易参数", "按交易介质分析"),
            dimension("term_entry_cap", "交易参数", "按终端读取能力分析"),
            dimension("wallet_id", "交易参数", "按钱包标识分析"),
            dimension("name", "交易参数", "按钱包名称分析"),
            dimension("acq_sett_curr_cde", "交易参数", "按受理方清算币种分析"),
            dimension("iss_sett_curr_cde", "交易参数", "按发卡方清算币种分析"),
            dimension("trans_scen_ind", "交易参数", "按IP用法分析"),
            dimension("bi_tag", "交易参数", "按B2B产品标识分析"),
            dimension("token_ind_ch", "交易参数", "按是否token发起分析"),
            dimension("iss_trans_resp_cde", "交易参数", "按发卡方响应码分析"),
            dimension("DEFINITION3", "交易参数", "按发卡方响应码名称分析"),
            dimension("acq_trans_resp_cde", "交易参数", "按收单方响应码分析"),
            dimension("DEFINITION2", "交易参数", "按收单方响应码名称分析"),
            dimension("kpi_ind", "交易参数", "按有效标识分析"),
            dimension("brand", "卡属性", "按卡品牌分析"),
            dimension("card_bin", "卡属性", "按卡bin分析"),
            dimension("card_attr_def", "卡属性", "按卡性质名称分析"),
            dimension("card_media_def", "卡属性", "按卡介质名称分析"),
            dimension("card_rank_cde_def", "卡属性", "按卡等级名称分析"),
            dimension("tid", "商户信息", "按TID分析"),
            dimension("mer_id", "商户信息", "按商户代码分析"),
            dimension("mer_addr_nm", "商户信息", "按商户名称分析"),
            dimension("mcc_cde", "商户信息", "按MCC分析"),
            dimension("china_mcc_cde_lvl_3", "商户信息", "按MCC分类分析"),
            dimension("bid", "会员信息", "按发卡BID分析"),
            dimension("org_name", "会员信息", "按发卡会员名称分析"),
            dimension("rep_reg_ch2", "会员信息", "按发卡会员分公司分析"),
            dimension("abd_mkt_ch2", "会员信息", "按发卡会员市场分析"),
            dimension("vip_type", "会员信息", "按发卡会员类型分析"),
            dimension("bid2", "会员信息", "按收单BID分析"),
            dimension("org_name2", "会员信息", "按收单会员名称分析"),
            dimension("rep_reg_ch3", "会员信息", "按收单会员分公司分析"),
            dimension("abd_mkt_ch3", "会员信息", "按收单会员市场分析"),
            dimension("vip_type2", "会员信息", "按收单会员类型分析"));

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
        if (metricId == null || !metricId.endsWith("_m")) {
            return Optional.empty();
        }
        String suffix = months == 1 ? "hb" : months == 12 ? "tb" : null;
        return suffix == null
                ? Optional.empty()
                : Optional.of(metricId.substring(0, metricId.length() - 1) + suffix);
    }

    private static AttributionDimension dimension(String id, String category, String description) {
        return dimension(id, category, description, "", "");
    }

    private static AttributionDimension dimension(
            String id, String category, String description, String aliases, String mappingHint) {
        return new AttributionDimension(
                id, QueryMetadataCatalog.displayName(id), category, description, true,
                aliases == null || aliases.isBlank() ? List.of() : List.of(aliases.split("、")),
                mappingHint == null ? "" : mappingHint);
    }

    private static Map<String, AttributionDimension> dimensions(AttributionDimension... definitions) {
        Map<String, AttributionDimension> result = new LinkedHashMap<>();
        for (AttributionDimension definition : definitions) {
            result.put(definition.id(), definition);
        }
        return Collections.unmodifiableMap(result);
    }

    public record AttributionDimension(
            String id,
            String name,
            String category,
            String description,
            boolean attributionEnabled,
            List<String> aliases,
            String mappingHint) {

        public AttributionDimension {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            mappingHint = mappingHint == null ? "" : mappingHint;
        }
    }
}
