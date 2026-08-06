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
    void acceptsTheSameOfficialJsonThatTheClientSends() throws Exception {
        mockMvc.perform(post("/api/mock/smartbi/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "dataSetId":"merchant_daily",
                                  "rows":["region_name"],
                                  "columns":["trans_amt"],
                                  "filters":[{"id":"1","name":"region_name","operation":"IN","values":["EAST","SOUTH"]}],
                                  "relationNode":{"childNodes":[],"filter":null,"relation":"AND","leaf":false},
                                  "orderBys":[{"fieldName":"trans_amt","type":"DESC","orderPriority":1}]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columnLabels").isArray())
                .andExpect(jsonPath("$.iterator").isArray())
                .andExpect(jsonPath("$.iterator[0][0].type").exists())
                .andExpect(jsonPath("$.iterator[0][0].displayValue").exists())
                .andExpect(jsonPath("$.iterator[0][0].value").exists())
                .andExpect(jsonPath("$.totalRowCount").value(-1));
    }

    @Test
    void expandsOfficialInclusiveRangePredicatesForMockDateRows() throws Exception {
        mockMvc.perform(post("/api/mock/smartbi/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "dataSetId":"merchant_daily",
                                  "rows":["sett_dt_Day"],
                                  "columns":["trans_amt"],
                                  "filters":[
                                    {"id":"1-from","name":"trade_date","operation":"GREATER_EQUALS","values":["2026-07-30"]},
                                    {"id":"1-to","name":"trade_date","operation":"LESS_EQUALS","values":["2026-08-01"]}
                                  ],
                                  "relationNode":{"childNodes":[],"filter":null,"relation":"AND","leaf":false},
                                  "orderBys":[]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.iterator.length()").value(3))
                .andExpect(jsonPath("$.iterator[0][0].value").value("2026-07-30"))
                .andExpect(jsonPath("$.iterator[2][0].value").value("2026-08-01"));
    }

    @Test
    void servesTheDeterministicAttributionScenarioThroughTheSmartBiRoute() throws Exception {
        mockMvc.perform(post("/api/mock/smartbi/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "dataSetId":"mock-dataset",
                                  "rows":["acq_ins_ch"],
                                  "columns":["trans_rmb_amt_m","trans_rmb_amt_hb"],
                                  "filters":[
                                    {"id":"1","name":"sett_dt_Month2","operation":"EQUALS","values":["2026-07"]}
                                  ],
                                  "relationNode":null,
                                  "orderBys":[
                                    {"fieldName":"trans_rmb_amt_m","type":"DESC","orderPriority":1}
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columnLabels[0]").value("acq_ins_ch"))
                .andExpect(jsonPath("$.columnLabels[1]").value("trans_rmb_amt_m"))
                .andExpect(jsonPath("$.iterator.length()").value(4))
                .andExpect(jsonPath("$.iterator[0][0].value").value("收单机构B"))
                .andExpect(jsonPath("$.iterator[0][2].value").isNumber());
    }
}
