package com.company.paymentanalysis.calculation;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

public record TrendPoint(
        String periodLabel,
        LocalDate periodStart,
        BigDecimal value) implements Serializable {
}
