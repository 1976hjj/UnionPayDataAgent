package com.company.paymentanalysis.calculation;

import com.company.paymentanalysis.normalize.NormalizedDataRow;
import java.math.BigDecimal;
import java.util.List;

public interface AnalysisCalculationEngine {

    ComparisonResult compare(
            BigDecimal subjectA,
            BigDecimal subjectB,
            ComparisonRequest request);

    RankingResult rank(
            List<NormalizedDataRow> rows,
            RankingRequest request);

    TrendSummary summarizeTrend(
            List<NormalizedDataRow> rows,
            TrendRequest request);
}
