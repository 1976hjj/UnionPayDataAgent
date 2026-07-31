package com.company.paymentanalysis.execution;

import com.company.paymentanalysis.analysis.QueryPlan;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AnalysisExecutionAuditLogger {

    private static final Logger log =
            LoggerFactory.getLogger(AnalysisExecutionAuditLogger.class);

    private final ObjectMapper objectMapper;

    public AnalysisExecutionAuditLogger(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void completed(
            QueryPlan plan,
            AnalysisExecutionResult result,
            String handlerName,
            long elapsedMillis) {
        log.info(
                "analysis completed traceId={} planId={} intent={} handler={} status={} "
                        + "queryCount={} warnings={} elapsedMs={}",
                result.traceId(),
                result.planId(),
                result.intent(),
                handlerName,
                result.status(),
                result.queryRecords().size(),
                result.warnings(),
                elapsedMillis);
        if (log.isDebugEnabled()) {
            log.debug(
                    "analysis audit traceId={} queryPlan={} queryRecords={} rawData={} "
                            + "calculationResult={} responseText={}",
                    result.traceId(),
                    json(plan),
                    json(result.queryRecords()),
                    json(result.rawData()),
                    json(result.calculationResult()),
                    result.responseText());
        }
    }

    public void failed(
            QueryPlan plan,
            String handlerName,
            RuntimeException exception,
            long elapsedMillis) {
        log.error(
                "analysis failed intent={} handler={} elapsedMs={} queryPlan={}",
                plan.intent(),
                handlerName,
                elapsedMillis,
                json(plan),
                exception);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return "<json serialization failed: " + exception.getMessage() + ">";
        }
    }
}
