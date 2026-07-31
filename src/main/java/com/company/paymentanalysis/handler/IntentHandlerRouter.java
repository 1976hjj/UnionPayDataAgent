package com.company.paymentanalysis.handler;

import com.company.paymentanalysis.analysis.AnalysisContext;
import com.company.paymentanalysis.analysis.IntentType;
import com.company.paymentanalysis.analysis.QueryPlan;
import com.company.paymentanalysis.execution.AnalysisExecutionResult;
import com.company.paymentanalysis.execution.AnalysisExecutionAuditLogger;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class IntentHandlerRouter {

    private final Map<IntentType, IntentHandler> handlerMap;
    private final AnalysisExecutionAuditLogger auditLogger;

    public IntentHandlerRouter(
            List<IntentHandler> handlers,
            AnalysisExecutionAuditLogger auditLogger) {
        this.auditLogger = auditLogger;
        EnumMap<IntentType, IntentHandler> registrations = new EnumMap<>(IntentType.class);
        for (IntentHandler handler : handlers == null ? List.<IntentHandler>of() : handlers) {
            if (handler == null) {
                throw new IllegalArgumentException("IntentHandler 列表不能包含 null");
            }
            IntentType intent = handler.support();
            if (intent == null) {
                throw new IllegalArgumentException(
                        "IntentHandler.support() 不能返回 null："
                                + handler.getClass().getName());
            }
            IntentHandler duplicate = registrations.putIfAbsent(intent, handler);
            if (duplicate != null) {
                throw new IllegalStateException(
                        "意图 "
                                + intent
                                + " 存在重复 Handler："
                                + duplicate.getClass().getName()
                                + "、"
                                + handler.getClass().getName());
            }
        }
        this.handlerMap = Collections.unmodifiableMap(registrations);
    }

    public AnalysisExecutionResult route(QueryPlan queryPlan, AnalysisContext context) {
        if (queryPlan == null) {
            throw new IllegalArgumentException("QueryPlan 不能为空");
        }
        if (queryPlan.intent() == null) {
            throw new IllegalArgumentException("QueryPlan.intent 不能为空");
        }
        IntentHandler handler = handlerMap.get(queryPlan.intent());
        if (handler == null) {
            throw new UnsupportedIntentException(queryPlan.intent());
        }
        long startedAt = System.nanoTime();
        try {
            AnalysisExecutionResult result = handler.execute(queryPlan, context);
            auditLogger.completed(
                    queryPlan,
                    result,
                    handler.getClass().getSimpleName(),
                    elapsedMillis(startedAt));
            return result;
        } catch (RuntimeException exception) {
            auditLogger.failed(
                    queryPlan,
                    handler.getClass().getSimpleName(),
                    exception,
                    elapsedMillis(startedAt));
            throw exception;
        }
    }

    public boolean supports(IntentType intent) {
        return intent != null && handlerMap.containsKey(intent);
    }

    private long elapsedMillis(long startedAt) {
        return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
    }
}
