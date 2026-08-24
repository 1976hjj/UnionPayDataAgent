package com.company.paymentanalysis.ragflow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ValueCandidateMatcherTest {

    @Test
    void acceptsExactValuesAndCompleteAsciiValuesInsideCompositeTerms() {
        assertThat(ValueCandidateMatcher.matches("POS", "POS")).isTrue();
        assertThat(ValueCandidateMatcher.matches("POS交易笔数", "POS")).isTrue();
        assertThat(ValueCandidateMatcher.matches("交易类型 POS", "POS")).isTrue();
        assertThat(ValueCandidateMatcher.matches("11", "11")).isTrue();
        assertThat(ValueCandidateMatcher.matches("澳大利亚", "澳大利亚")).isTrue();
        assertThat(ValueCandidateMatcher.matches("内卡外用", "内卡外用")).isTrue();
    }

    @Test
    void rejectsSubstringCodesReverseContainmentAndSameDimensionNoise() {
        assertThat(ValueCandidateMatcher.matches("VISA双标芯片卡", "SA")).isFalse();
        assertThat(ValueCandidateMatcher.matches("澳大利亚", "澳大利亚元")).isFalse();
        assertThat(ValueCandidateMatcher.matches("年 2026", "2019")).isFalse();
        assertThat(ValueCandidateMatcher.matches("2025-09-11", "11")).isFalse();
        assertThat(ValueCandidateMatcher.matches("日 2025-09-11", "11")).isFalse();
        assertThat(ValueCandidateMatcher.matches("2025-10-23", "23")).isFalse();
        assertThat(ValueCandidateMatcher.matches("交易渠道代码 11", "11")).isFalse();
        assertThat(ValueCandidateMatcher.matches("是否成功", "是")).isFalse();
        assertThat(ValueCandidateMatcher.matches("东南亚", "南亚")).isFalse();
        assertThat(ValueCandidateMatcher.matches("内卡外用交易", "内卡外用")).isFalse();
    }

    @Test
    void acceptsOnlyExplicitAliasesThatFullyMatchTheSourceTerm() {
        String description = "地区; aliases=[\"东盟\",\"东南亚地区\"]";

        assertThat(ValueCandidateMatcher.matches("东盟", "东南亚", description)).isTrue();
        assertThat(ValueCandidateMatcher.matches("东南亚地区", "东南亚", description)).isTrue();
        assertThat(ValueCandidateMatcher.matches("东盟交易", "东南亚", description)).isFalse();
        assertThat(ValueCandidateMatcher.matches("东亚", "东南亚", description)).isFalse();
    }
}
