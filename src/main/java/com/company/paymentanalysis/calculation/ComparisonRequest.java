package com.company.paymentanalysis.calculation;

import java.io.Serializable;
import java.math.RoundingMode;
import java.util.List;

public record ComparisonRequest(
        String subjectALabel,
        String subjectBLabel,
        ComparisonExpression expression,
        List<CalculationType> calculations,
        Integer rateScale,
        RoundingMode roundingMode) implements Serializable {

    public ComparisonRequest {
        subjectALabel = subjectALabel == null ? "" : subjectALabel;
        subjectBLabel = subjectBLabel == null ? "" : subjectBLabel;
        expression = expression == null ? ComparisonExpression.COMPARE_ONLY : expression;
        calculations = calculations == null ? List.of() : List.copyOf(calculations);
        rateScale = rateScale == null ? 4 : rateScale;
        if (rateScale < 0) {
            throw new IllegalArgumentException("rateScale 不能小于 0");
        }
        roundingMode = roundingMode == null ? RoundingMode.HALF_UP : roundingMode;
    }

    public ComparisonRequest(
            String subjectALabel,
            String subjectBLabel,
            ComparisonExpression expression,
            List<CalculationType> calculations,
            Integer rateScale) {
        this(
                subjectALabel,
                subjectBLabel,
                expression,
                calculations,
                rateScale,
                RoundingMode.HALF_UP);
    }
}
