package com.company.paymentanalysis.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class QueryMetadataCatalogTest {

    @Test
    void exposesEveryProductionMetricAndDimensionFromTheProvidedModel() {
        assertThat(QueryMetadataCatalog.metricIds()).hasSize(24);
        assertThat(QueryMetadataCatalog.dimensionIds()).hasSize(71);
        assertThat(QueryMetadataCatalog.metricIds()).contains(
                "trans_rmb_amt_m", "acpt_trans_rmb_amt_hb", "sh_cg_num_tb");
        assertThat(QueryMetadataCatalog.dimensionIds()).contains(
                "sett_dt_Day2", "ins_ins_ch", "kpi_ind", "china_mcc_cde_lvl_3", "mer_id");
    }

    @Test
    void usesProductionSmartBiFieldsWithoutInventedAliases() {
        assertThat(QueryMetadataCatalog.smartBiField("acpt_trans_rmb_amt_m"))
                .isEqualTo("acpt_trans_rmb_amt_m");
        assertThat(QueryMetadataCatalog.smartBiFilterField("sett_dt_Day2"))
                .isEqualTo("sett_dt_Day2");
        assertThat(QueryMetadataCatalog.displayName("ins_ins_ch")).isEqualTo("发卡机构名称");
        assertThat(QueryMetadataCatalog.displayNameBySmartBiField("acq_mkt_ch")).isEqualTo("收单市场");
        assertThatThrownBy(() -> QueryMetadataCatalog.smartBiField("transactionAmount"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
