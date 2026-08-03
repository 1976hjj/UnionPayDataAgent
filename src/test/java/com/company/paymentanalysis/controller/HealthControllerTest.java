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
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthEndpointReturnsUp() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("payment-analysis"));
    }

    @Test
    void modelOptionsComeFromTheBackendConfiguration() throws Exception {
        mockMvc.perform(get("/api/system/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultModel").value("glm-4.7-flash"))
                .andExpect(jsonPath("$.models[0]").value("glm-4.7"))
                .andExpect(jsonPath("$.models[1]").value("glm-4.7-flashx"))
                .andExpect(jsonPath("$.models[2]").value("glm-4.7-flash"))
                .andExpect(jsonPath("$.models[3]").value("glm-4-flash-250414"));
    }

    @Test
    void dependencyStatusTargetsOnlyTheSelectedModel() throws Exception {
        mockMvc.perform(get("/api/system/dependencies").param("model", "glm-4.7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dependencies[1].code").value("llm"))
                .andExpect(jsonPath("$.dependencies[1].name").value("glm-4.7"));
    }
}
