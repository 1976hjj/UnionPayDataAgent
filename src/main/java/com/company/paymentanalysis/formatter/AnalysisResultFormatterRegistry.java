package com.company.paymentanalysis.formatter;

import com.company.paymentanalysis.analysis.QueryPlan;
import com.company.paymentanalysis.execution.AnalysisExecutionResult;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AnalysisResultFormatterRegistry {

    private final Map<com.company.paymentanalysis.analysis.IntentType, AnalysisResultFormatter> formatters;

    public AnalysisResultFormatterRegistry(List<AnalysisResultFormatter> formatters) {
        EnumMap<com.company.paymentanalysis.analysis.IntentType, AnalysisResultFormatter> values =
                new EnumMap<>(com.company.paymentanalysis.analysis.IntentType.class);
        for (AnalysisResultFormatter formatter : formatters) {
            if (values.putIfAbsent(formatter.support(), formatter) != null) {
                throw new IllegalStateException("重复的结果格式化器：" + formatter.support());
            }
        }
        this.formatters = Map.copyOf(values);
    }

    public String format(AnalysisExecutionResult result, QueryPlan plan) {
        if (result == null || plan == null) {
            return "";
        }
        AnalysisResultFormatter formatter = formatters.get(result.intent());
        return formatter == null ? "" : formatter.format(result, plan);
    }
}
