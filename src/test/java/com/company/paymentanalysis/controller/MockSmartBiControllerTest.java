package com.company.paymentanalysis.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class MockSmartBiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void acceptsSmartBiJsonAndReturnsFixedRows() throws Exception {
        mockMvc.perform(post("/api/mock/smartbi/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "dataSetId": "Iff8080810196b306b3067b2a0196b3067b2a0000",
                                  "rows": ["sett_dt_Month2", "JYJZ_NAME"],
                                  "columns": ["acpt_trans_rmb_amt_m", "acpt_trans_rmb_amt_hb"],
                                  "filters": [
                                    {"id":"1","name":"sett_dt_Month2","operation":"EQUALS","values":["2026-07"]},
                                    {"id":"2","name":"sett_dt_Month2","operation":"EQUALS","values":["2026-06"]}
                                  ],
                                  "relationNode": {
                                    "childNodes": [],
                                    "relation": "AND",
                                    "leaf": "false"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metadata.source").value("Mock SmartBI route"))
                .andExpect(jsonPath("$.metadata.periodsCombined").value(true))
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[0].memberName").value("芯片卡"))
                .andExpect(jsonPath("$.data[2].direction").value("DOWN"));
    }
}
