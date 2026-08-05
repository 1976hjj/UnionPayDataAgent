package com.company.paymentanalysis.query;

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

public final class QueryMetadataCatalog {

    /**
     * Production data-model dictionary. IDs are the SmartBI field names from
     * “生产模型.xlsx”, so no invented intermediate field name is sent to SmartBI.
     * The page's current small capability display is intentionally independent
     * from this catalog and is not changed in this step.
     */
    private static final Map<String, FieldDefinition> METRICS = orderedMap(
            metric("trans_cnt_m", "总交易笔数"),
            metric("trans_amt_m", "原币总金额"),
            metric("trans_rmb_amt_m", "人民币总金额"),
            metric("acpt_cnt_m", "承兑笔数"),
            metric("acpt_trans_amt_m", "原币承兑金额"),
            metric("acpt_trans_rmb_amt_m", "人民币承兑金额"),
            metric("sh_jy_num_m", "当日商户数（有交易）"),
            metric("sh_cg_num_m", "当日商户数（有成功交易）"),
            metric("trans_cnt_tb", "总交易笔数同比"),
            metric("trans_cnt_hb", "总交易笔数环比"),
            metric("trans_amt_tb", "原币总金额同比"),
            metric("trans_amt_hb", "原币总金额环比"),
            metric("trans_rmb_amt_tb", "人民币总金额同比"),
            metric("trans_rmb_amt_hb", "人民币总金额环比"),
            metric("acpt_cnt_tb", "承兑笔数同比"),
            metric("acpt_cnt_hb", "承兑笔数环比"),
            metric("acpt_trans_amt_tb", "原币承兑金额同比"),
            metric("acpt_trans_amt_hb", "原币承兑金额环比"),
            metric("acpt_trans_rmb_amt_tb", "人民币承兑金额同比"),
            metric("acpt_trans_rmb_amt_hb", "人民币承兑金额环比"),
            metric("sh_jy_num_tb", "当日商户数（有交易）同比"),
            metric("sh_jy_num_hb", "当日商户数（有交易）环比"),
            metric("sh_cg_num_tb", "当日商户数（有成功交易）同比"),
            metric("sh_cg_num_hb", "当日商户数（有成功交易）环比"));

    private static final Map<String, FieldDefinition> DIMENSIONS = orderedMap(
            dimension("sett_dt_Year2", "年"),
            dimension("sett_dt_Month2", "月"),
            dimension("sett_dt_Day2", "日"),
            dimension("acq_reg_ch", "收单分公司"),
            dimension("acq_mkt_ch", "收单市场", "收单侧境内外市场、国家或区域；海外或洲际且未指定发卡方时优先"),
            dimension("acq_reg_cde", "收单市场代码"),
            dimension("iss_dq_ch", "发卡分公司"),
            dimension("iss_sc_ch", "发卡市场", "发卡侧市场、国家或区域；仅在用户明确发卡方语义时优先"),
            dimension("iss_reg_cde", "发卡市场代码"),
            dimension("reg_nm_lvl_1", "中国大陆受理省市", "仅用于中国大陆受理省级地域，如省、自治区、直辖市；不用于海外或洲际"),
            dimension("reg_nm_lvl_2", "中国大陆受理区县", "仅用于中国大陆受理区县级地域；不用于海外或洲际"),
            dimension("acq_ins_cde", "收单机构代码"),
            dimension("iss_ins_cde", "发卡机构代码"),
            dimension("acq_ins_ch", "收单机构名称"),
            dimension("ins_ins_ch", "发卡机构名称"),
            dimension("fwd_ins_cde", "转发机构代码"),
            dimension("recv_ins_cde", "接受机构代码"),
            dimension("recv_ins_nm", "接受机构名称"),
            dimension("eci", "电子商务标识"),
            dimension("mm_sh_sign", "免验密码标识"),
            dimension("trans_nms", "交易类型"),
            dimension("acq_pos_cond_cde", "服务点条件码"),
            dimension("instalment", "分期期数"),
            dimension("mpay_def", "移动支付"),
            dimension("resp_cde", "响应码"),
            dimension("DEFINITION", "响应码名称"),
            dimension("proc_ind", "处理标记"),
            dimension("rev_ind", "冲正标记"),
            dimension("trans_cde", "交易代码"),
            dimension("trans_nm", "交易代码名称"),
            dimension("trans_mod_def", "交易模式名称"),
            dimension("channel", "交易渠道代码"),
            dimension("channel_def", "交易渠道名称"),
            dimension("curr_cde", "交易货币代码"),
            dimension("curr_nm_ch", "交易货币名称"),
            dimension("srv_entry_mod", "服务点输入方式"),
            dimension("ic_cond_cde", "IC卡条件代码"),
            dimension("JYJZ_NAME", "交易介质"),
            dimension("term_entry_cap", "终端读取能力"),
            dimension("wallet_id", "钱包标识"),
            dimension("name", "钱包名称"),
            dimension("acq_sett_curr_cde", "受理方清算币种"),
            dimension("iss_sett_curr_cde", "发卡方清算币种"),
            dimension("trans_scen_ind", "IP用法"),
            dimension("bi_tag", "B2B产品标识"),
            dimension("token_ind_ch", "是否token发起"),
            dimension("iss_trans_resp_cde", "发卡方响应码"),
            dimension("DEFINITION3", "发卡方响应码名称"),
            dimension("acq_trans_resp_cde", "收单方响应码"),
            dimension("DEFINITION2", "收单方响应码名称"),
            dimension("kpi_ind", "有效标识"),
            dimension("brand", "卡品牌"),
            dimension("card_bin", "卡bin"),
            dimension("card_attr_def", "卡性质名称"),
            dimension("card_media_def", "卡介质名称"),
            dimension("card_rank_cde_def", "卡等级名称"),
            dimension("tid", "TID"),
            dimension("mer_id", "商户代码"),
            dimension("mer_addr_nm", "商户名称"),
            dimension("mcc_cde", "MCC"),
            dimension("china_mcc_cde_lvl_3", "MCC分类"),
            dimension("bid", "发卡BID"),
            dimension("org_name", "发卡会员名称"),
            dimension("rep_reg_ch2", "发卡会员分公司"),
            dimension("abd_mkt_ch2", "发卡会员市场"),
            dimension("vip_type", "发卡会员类型"),
            dimension("bid2", "收单BID"),
            dimension("org_name2", "收单会员名称"),
            dimension("rep_reg_ch3", "收单会员分公司"),
            dimension("abd_mkt_ch3", "收单会员市场"),
            dimension("vip_type2", "收单会员类型"));

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
                .map(field -> field.id() + "=" + field.displayName()
                        + (field.promptHint().isBlank() ? "" : "（" + field.promptHint() + "）"))
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

    private static FieldDefinition metric(String id, String displayName) {
        return new FieldDefinition(id, displayName, id, id, "");
    }

    private static FieldDefinition dimension(String id, String displayName) {
        return dimension(id, displayName, "");
    }

    private static FieldDefinition dimension(String id, String displayName, String promptHint) {
        return new FieldDefinition(id, displayName, id, id, promptHint);
    }

    private static Map<String, FieldDefinition> allFields() {
        Map<String, FieldDefinition> result = new LinkedHashMap<>(METRICS);
        result.putAll(DIMENSIONS);
        return result;
    }

    private record FieldDefinition(
            String id, String displayName, String smartBiField, String smartBiFilterField, String promptHint) {
    }
}
