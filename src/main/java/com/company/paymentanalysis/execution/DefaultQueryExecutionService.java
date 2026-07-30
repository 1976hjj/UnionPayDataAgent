package com.company.paymentanalysis.execution;

import com.company.paymentanalysis.analysis.QueryPlan;
import com.company.paymentanalysis.normalize.NormalizedDataRow;
import com.company.paymentanalysis.normalize.SmartBiResultNormalizer;
import com.company.paymentanalysis.smartbi.SmartBiClient;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryRequest;
import com.company.paymentanalysis.smartbi.SmartBiModels.QueryResponse;
import com.company.paymentanalysis.smartbi.SmartBiQueryBuilder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class DefaultQueryExecutionService implements QueryExecutionService {

    private final SmartBiQueryBuilder queryBuilder;
    private final SmartBiClient smartBiClient;
    private final SmartBiResultNormalizer resultNormalizer;
    private final ObjectMapper objectMapper;

    public DefaultQueryExecutionService(
            SmartBiQueryBuilder queryBuilder,
            SmartBiClient smartBiClient,
            SmartBiResultNormalizer resultNormalizer,
            ObjectMapper objectMapper) {
        this.queryBuilder = queryBuilder;
        this.smartBiClient = smartBiClient;
        this.resultNormalizer = resultNormalizer;
        this.objectMapper = objectMapper;
    }

    @Override
    public AnalysisExecutionResult execute(QueryPlan queryPlan) {
        if (queryPlan == null) {
            throw new IllegalArgumentException("QueryPlan 不能为空");
        }
        if (!queryPlan.needsDataQuery()) {
            throw new IllegalArgumentException("当前 QueryPlan 不需要执行数据查询");
        }

        String traceId = UUID.randomUUID().toString();
        String planId = UUID.randomUUID().toString();
        String queryId = UUID.randomUUID().toString();
        String requestJson = "";
        long startedAt = System.nanoTime();
        try {
            QueryRequest request = queryBuilder.build(queryPlan);
            requestJson = serialize(request);
            QueryResponse response = smartBiClient.query(request);
            List<NormalizedDataRow> normalizedRows =
                    resultNormalizer.normalize(response, queryPlan);
            long elapsed = elapsedMillis(startedAt);
            QueryExecutionRecord record = new QueryExecutionRecord(
                    queryId,
                    requestJson,
                    response,
                    elapsed,
                    QueryExecutionStatus.SUCCESS,
                    "");
            ExecutionStatus status = normalizedRows.isEmpty()
                    ? ExecutionStatus.NO_DATA
                    : ExecutionStatus.SUCCESS;
            List<String> warnings = normalizedRows.isEmpty()
                    ? List.of("SmartBI 查询成功，但没有匹配数据")
                    : List.of();
            return new AnalysisExecutionResult(
                    traceId,
                    planId,
                    queryPlan.intent(),
                    status,
                    List.of(record),
                    response,
                    normalizedRows,
                    warnings,
                    "");
        } catch (RuntimeException exception) {
            long elapsed = elapsedMillis(startedAt);
            String errorMessage = rootMessage(exception);
            QueryExecutionRecord record = new QueryExecutionRecord(
                    queryId,
                    requestJson,
                    null,
                    elapsed,
                    QueryExecutionStatus.FAILED,
                    errorMessage);
            return new AnalysisExecutionResult(
                    traceId,
                    planId,
                    queryPlan.intent(),
                    ExecutionStatus.QUERY_FAILED,
                    List.of(record),
                    null,
                    null,
                    List.of(errorMessage),
                    "");
        }
    }

    private String serialize(QueryRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("SmartBI 请求 JSON 序列化失败", exception);
        }
    }

    private long elapsedMillis(long startedAt) {
        return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
    }

    private String rootMessage(RuntimeException exception) {
        Throwable root = exception;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }
}
