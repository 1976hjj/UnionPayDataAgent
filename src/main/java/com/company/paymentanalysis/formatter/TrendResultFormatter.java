package com.company.paymentanalysis.formatter;

import com.company.paymentanalysis.analysis.IntentType;
import com.company.paymentanalysis.analysis.QueryPlan;
import com.company.paymentanalysis.calculation.TrendSummary;
import com.company.paymentanalysis.execution.AnalysisExecutionResult;
import org.springframework.stereotype.Component;

@Component
public class TrendResultFormatter implements AnalysisResultFormatter {

    @Override
    public IntentType support() {
        return IntentType.TREND_QUERY;
    }

    @Override
    public String format(AnalysisExecutionResult execution, QueryPlan plan) {
        if (!(execution.calculationResult() instanceof TrendSummary result)
                || result.validPointCount() == 0) {
            return "趋势查询没有可用数据。";
        }
        return "共" + result.validPointCount() + "个时间点，"
                + result.firstPoint().periodLabel() + "为" + result.firstPoint().value().toPlainString()
                + "，" + result.lastPoint().periodLabel() + "为" + result.lastPoint().value().toPlainString()
                + "，累计变化" + result.totalDifference().toPlainString()
                + "，趋势为" + result.direction() + "。";
    }
}
