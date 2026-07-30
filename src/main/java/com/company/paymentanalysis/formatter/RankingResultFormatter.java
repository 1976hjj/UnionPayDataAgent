package com.company.paymentanalysis.formatter;

import com.company.paymentanalysis.analysis.IntentType;
import com.company.paymentanalysis.analysis.QueryPlan;
import com.company.paymentanalysis.calculation.RankingResult;
import com.company.paymentanalysis.execution.AnalysisExecutionResult;
import org.springframework.stereotype.Component;

@Component
public class RankingResultFormatter implements AnalysisResultFormatter {

    @Override
    public IntentType support() {
        return IntentType.RANK_QUERY;
    }

    @Override
    public String format(AnalysisExecutionResult execution, QueryPlan plan) {
        if (!(execution.calculationResult() instanceof RankingResult result)
                || result.items().isEmpty()) {
            return "排名查询没有可用数据。";
        }
        return "已按指标" + result.direction() + "排序，返回"
                + result.actualCount() + "条结果；第一名为"
                + result.items().get(0).dimensionValues()
                + "，指标值" + result.items().get(0).metricValue().toPlainString() + "。";
    }
}
