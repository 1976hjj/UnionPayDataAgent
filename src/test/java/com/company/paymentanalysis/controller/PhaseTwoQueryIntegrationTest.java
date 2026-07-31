package com.company.paymentanalysis.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
            "chat.memory.redis-enabled=false",
            "llm.mock-enabled=true",
            "server.port=18082",
            "smartbi.base-url=http://localhost:18082"
        })
@AutoConfigureMockMvc
class PhaseTwoQueryIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void executesTrendThroughHttpSmartBiAndJavaCalculation() throws Exception {
        query("phase2-trend", "\u6700\u8fd16\u4e2a\u6708\u4ea4\u6613\u91d1\u989d\u8d70\u52bf")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.result.rows.length()").value(3))
                .andExpect(jsonPath("$.result.rows[0].period").value("2026-05"))
                .andExpect(jsonPath("$.workflowSteps[4].status").value("COMPLETED"))
                .andExpect(jsonPath("$.executionEngine")
                        .value(org.hamcrest.Matchers.containsString("Java Calculation Engine")));
    }

    @Test
    void executesTopNThroughHttpSmartBiAndJavaRanking() throws Exception {
        query(
                        "phase2-rank",
                        "6\u6708\u4ea4\u6613\u91d1\u989d\u6700\u9ad8\u7684\u524d3\u4e2a\u6536\u5355\u5730\u533a")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.rows.length()").value(3))
                .andExpect(jsonPath("$.result.rows[0].rank").value("1"))
                .andExpect(jsonPath("$.result.rows[0].transactionAmount").exists());
    }

    @Test
    void comparesJuneAndJulyWithTwoPreservedSubjectsAndJavaDifference() throws Exception {
        query("phase2-compare-months", "6月比7月少了多少人民币总金额")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.result.rows.length()").value(2))
                .andExpect(jsonPath("$.result.rows[0].comparisonSubject").value("6月"))
                .andExpect(jsonPath("$.result.rows[1].comparisonSubject").value("7月"))
                .andExpect(jsonPath("$.reply", org.hamcrest.Matchers.containsString("差额")));
    }

    @Test
    void comparesUnitedKingdomAndFranceInTheRequestedDirection() throws Exception {
        query("phase2-compare-countries", "英国比法国多多少交易金额")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.rows[0].comparisonSubject").value("英国"))
                .andExpect(jsonPath("$.result.rows[1].comparisonSubject").value("法国"))
                .andExpect(jsonPath("$.reply", org.hamcrest.Matchers.containsString("英国")));
    }

    @Test
    void comparesCurrentMonthAndPreviousMonthUsingFixedClock() throws Exception {
        query("phase2-compare-relative", "本月比上月增长多少交易金额")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.rows[0].comparisonSubject").value("本月"))
                .andExpect(jsonPath("$.result.rows[1].comparisonSubject").value("上月"))
                .andExpect(jsonPath("$.reply", org.hamcrest.Matchers.containsString("变化率")));
    }

    @Test
    void queriesEveryMonthOfCurrentYearAsTrend() throws Exception {
        query("phase2-trend-year", "今年每个月交易笔数")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.queryPlan.filters[0].values[0]").value("2026-01-01"))
                .andExpect(jsonPath("$.result.rows[0].transactionCount").exists());
    }

    @Test
    void ranksBottomThreeCountriesAscending() throws Exception {
        query("phase2-rank-bottom", "交易笔数最低的3个国家")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.rows.length()").value(3))
                .andExpect(jsonPath("$.reply", org.hamcrest.Matchers.containsString("ASC")));
    }

    @Test
    void returnsHighestAcquiringInstitutionWithDefaultLimitOne() throws Exception {
        query("phase2-rank-institution", "哪个收单机构交易金额最高")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.rows.length()").value(1))
                .andExpect(jsonPath("$.result.rows[0].acquiringInstitution").exists());
    }

    private org.springframework.test.web.servlet.ResultActions query(
            String sessionId,
            String message) throws Exception {
        return mockMvc.perform(post("/api/chat/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "userId": "phase2-user",
                          "sessionId": "%s",
                          "message": "%s",
                          "context": null
                        }
                        """.formatted(sessionId, message)));
    }

    @TestConfiguration
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock phaseTwoClock() {
            return Clock.fixed(
                    Instant.parse("2026-07-31T00:00:00Z"),
                    ZoneOffset.UTC);
        }
    }
}
