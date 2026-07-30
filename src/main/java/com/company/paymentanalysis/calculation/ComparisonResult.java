package com.company.paymentanalysis.calculation;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

public record ComparisonResult(
        String subjectALabel,
        String subjectBLabel,
        BigDecimal subjectAValue,
        BigDecimal subjectBValue,
        BigDecimal signedDifference,
        BigDecimal displayDifference,
        BigDecimal changeRate,
        ComparisonRelation relation,
        String formulaDescription,
        List<String> warnings) implements Serializable {

    public ComparisonResult {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        formulaDescription = formulaDescription == null ? "" : formulaDescription;
    }
}
