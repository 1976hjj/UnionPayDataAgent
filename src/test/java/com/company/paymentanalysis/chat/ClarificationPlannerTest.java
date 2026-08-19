package com.company.paymentanalysis.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ClarificationPlannerTest {

    @Test
    void fallbackUsesOnlyTheProvidedMissingFactsAndExamples() {
        String reply = ClarificationPlanner.noOp().plan(new ClarificationPlanner.ClarificationRequest(
                "ATTRIBUTION", "最近交易情况为什么下跌",
                List.of(
                        new ClarificationPlanner.MissingItem("metric", "分析度量"),
                        new ClarificationPlanner.MissingItem("period", "分析周期"),
                        new ClarificationPlanner.MissingItem("comparison", "对比基准")),
                List.of(), List.of("交易金额", "交易笔数")), "model");

        assertThat(reply).contains("分析度量", "分析周期", "对比基准", "交易金额", "交易笔数");
        assertThat(reply).doesNotContain("继续调整当前归因模板");
    }
}
