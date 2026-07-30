package com.company.paymentanalysis.formatter;

import com.company.paymentanalysis.analysis.IntentType;
import com.company.paymentanalysis.analysis.QueryPlan;
import com.company.paymentanalysis.calculation.ComparisonResult;
import com.company.paymentanalysis.execution.AnalysisExecutionResult;
import org.springframework.stereotype.Component;

@Component
public class ComparisonResultFormatter implements AnalysisResultFormatter {

    @Override
    public IntentType support() {
        return IntentType.COMPARE_QUERY;
    }

    @Override
    public String format(AnalysisExecutionResult execution, QueryPlan plan) {
        if (!(execution.calculationResult() instanceof ComparisonResult result)) {
            return execution.warnings().isEmpty()
                    ? "对比查询没有完整数据。"
                    : String.join("；", execution.warnings());
        }
        String rate = result.changeRate() == null
                ? ""
                : "，变化率为" + result.changeRate().movePointRight(2).stripTrailingZeros().toPlainString() + "%";
        return result.subjectALabel() + "为" + result.subjectAValue().toPlainString()
                + "，" + result.subjectBLabel() + "为" + result.subjectBValue().toPlainString()
                + "，差额为" + result.displayDifference().toPlainString() + rate + "。";
    }
}
