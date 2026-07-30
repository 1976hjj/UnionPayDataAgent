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
    void returnsFixedStructuredMetadata() throws Exception {
        mockMvc.perform(get("/api/metadata"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("mock-v1"))
                .andExpect(jsonPath("$.metrics.length()").value(3))
                .andExpect(jsonPath("$.dimensions.length()").value(4))
                .andExpect(jsonPath("$.metrics[0].name").value("交易金额"))
                .andExpect(jsonPath("$.dimensions[1].name").value("地区"));
    }
}
