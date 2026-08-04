package com.company.paymentanalysis.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class MetadataControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsTheProductionQueryMetadata() throws Exception {
        mockMvc.perform(get("/api/metadata"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("production-v1"))
                .andExpect(jsonPath("$.metrics.length()").value(24))
                .andExpect(jsonPath("$.dimensions.length()").value(71))
                .andExpect(jsonPath("$.metrics[0].id").value("trans_cnt_m"))
                .andExpect(jsonPath("$.metrics[0].name").value("总交易笔数"))
                .andExpect(jsonPath("$.dimensions[0].name").value("年"))
                .andExpect(jsonPath("$.dimensions[1].id").value("sett_dt_Month2"))
                .andExpect(jsonPath("$.dimensions[4].id").value("acq_mkt_ch"));
    }
}
