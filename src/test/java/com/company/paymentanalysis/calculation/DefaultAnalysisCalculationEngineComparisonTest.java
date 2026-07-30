package com.company.paymentanalysis.calculation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.junit.jupiter.api.Test;

class DefaultAnalysisCalculationEngineComparisonTest {

    private final AnalysisCalculationEngine engine = new DefaultAnalysisCalculationEngine();

    @Test
    void calculatesLessThanSemanticsAndKeepsSignedDifference() {
        ComparisonResult result = engine.compare(
                new BigDecimal("800"),
                new BigDecimal("1000"),
                request(ComparisonExpression.A_LESS_THAN_B, 4));

        assertThat(result.signedDifference()).isEqualByComparingTo("-200");
        assertThat(result.displayDifference()).isEqualByComparingTo("200");
        assertThat(result.changeRate()).isEqualByComparingTo("0.2000");
        assertThat(result.relation()).isEqualTo(ComparisonRelation.A_LESS_THAN_B);
        assertThat(result.formulaDescription()).contains("800-1000 = -200");
    }

    @Test
    void calculatesMoreThanSemantics() {
        ComparisonResult result = engine.compare(
                new BigDecimal("1200"),
                new BigDecimal("1000"),
                request(ComparisonExpression.A_MORE_THAN_B, 4));

        assertThat(result.signedDifference()).isEqualByComparingTo("200");
        assertThat(result.displayDifference()).isEqualByComparingTo("200");
        assertThat(result.changeRate()).isEqualByComparingTo("0.2000");
        assertThat(result.relation()).isEqualTo(ComparisonRelation.A_GREATER_THAN_B);
    }

    @Test
    void identifiesEqualValues() {
        ComparisonResult result = engine.compare(
                new BigDecimal("1000.00"),
                new BigDecimal("1000"),
                request(ComparisonExpression.COMPARE_ONLY, 4));

        assertThat(result.relation()).isEqualTo(ComparisonRelation.EQUAL);
        assertThat(result.signedDifference()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void warnsAndReturnsNullRateWhenBaseIsZero() {
        ComparisonResult result = engine.compare(
                new BigDecimal("100"),
                BigDecimal.ZERO,
                request(ComparisonExpression.A_MORE_THAN_B, 4));

        assertThat(result.changeRate()).isNull();
        assertThat(result.warnings()).containsExactly("基期为0，无法计算常规变化率");
    }

    @Test
    void calculatesZeroAgainstPositiveBase() {
        ComparisonResult result = engine.compare(
                BigDecimal.ZERO,
                new BigDecimal("100"),
                request(ComparisonExpression.A_LESS_THAN_B, 4));

        assertThat(result.displayDifference()).isEqualByComparingTo("100");
        assertThat(result.changeRate()).isEqualByComparingTo("1.0000");
    }

    @Test
    void preservesDecimalAndLargeAmountPrecision() {
        ComparisonResult decimal = engine.compare(
                new BigDecimal("800.125"),
                new BigDecimal("1000.375"),
                request(ComparisonExpression.A_MINUS_B, 6));
        ComparisonResult large = engine.compare(
                new BigDecimal("999999999999999999999.99"),
                new BigDecimal("888888888888888888888.88"),
                request(ComparisonExpression.A_MINUS_B, 4));

        assertThat(decimal.signedDifference()).isEqualByComparingTo("-200.250");
        assertThat(large.signedDifference())
                .isEqualByComparingTo("111111111111111111111.11");
    }

    @Test
    void supportsRateScaleAndHalfUpRounding() {
        ComparisonRequest request = new ComparisonRequest(
                "A",
                "B",
                ComparisonExpression.A_MINUS_B,
                List.of(CalculationType.ABSOLUTE_DIFFERENCE, CalculationType.CHANGE_RATE),
                2,
                RoundingMode.HALF_UP);

        ComparisonResult result =
                engine.compare(new BigDecimal("1.005"), BigDecimal.ONE, request);

        assertThat(result.changeRate()).isEqualByComparingTo("0.01");
    }

    @Test
    void compareOnlyDoesNotCalculateRateUnlessRequested() {
        ComparisonRequest request = new ComparisonRequest(
                "A",
                "B",
                ComparisonExpression.COMPARE_ONLY,
                List.of(CalculationType.RELATION),
                4);

        ComparisonResult result =
                engine.compare(new BigDecimal("10"), new BigDecimal("8"), request);

        assertThat(result.changeRate()).isNull();
        assertThat(result.relation()).isEqualTo(ComparisonRelation.A_GREATER_THAN_B);
    }

    private ComparisonRequest request(ComparisonExpression expression, int scale) {
        return new ComparisonRequest(
                "A",
                "B",
                expression,
                List.of(CalculationType.ABSOLUTE_DIFFERENCE, CalculationType.CHANGE_RATE),
                scale);
    }
}
