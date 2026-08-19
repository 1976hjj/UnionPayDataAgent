package com.company.paymentanalysis.controller;

import com.company.paymentanalysis.attribution.AttributionCatalog;
import com.company.paymentanalysis.attribution.AttributionCatalog.AttributionDimension;
import com.company.paymentanalysis.attribution.AttributionExecutionService;
import com.company.paymentanalysis.attribution.AttributionModels.AttributionRequest;
import com.company.paymentanalysis.attribution.AttributionModels.AttributionResponse;
import com.company.paymentanalysis.attribution.AttributionPolicyProperties;
import com.company.paymentanalysis.attribution.AttributionWorkflowService.WorkflowEvent;
import com.company.paymentanalysis.audit.ProcessAuditLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.server.ResponseStatusException;

/** Legacy attribution API backed by the shared execution boundary. */
@RestController
@RequestMapping("/api/attribution")
public class AttributionController {

    private final AttributionExecutionService executionService;
    private final AttributionPolicyProperties policy;
    private final ObjectMapper objectMapper;
    private final ProcessAuditLog auditLog;

    public AttributionController(
            AttributionExecutionService executionService,
            AttributionPolicyProperties policy,
            ObjectMapper objectMapper,
            ProcessAuditLog auditLog) {
        this.executionService = executionService;
        this.policy = policy;
        this.objectMapper = objectMapper;
        this.auditLog = auditLog;
    }

    @PostMapping("/analyze")
    public AttributionResponse analyze(@RequestBody AttributionRequest request) {
        try {
            try (ProcessAuditLog.AuditScope scope = auditLog.start("attribution.analyze", Map.of(
                    "userId", request == null || request.userId() == null ? "" : request.userId(),
                    "conversationId", request == null || request.conversationId() == null ? "" : request.conversationId(),
                    "request", request == null ? "" : request))) {
                AttributionResponse response = executionService.execute(request).response();
                scope.completed(Map.of(
                        "status", response.status(),
                        "queryCount", response.queryCount(),
                        "report", response.report(),
                        "stop", response.stop() == null ? "" : response.stop().code(),
                        "conversationOutcome", reportGenerated(response) ? "success" : "failed"));
                return response;
            }
        } catch (IllegalArgumentException exception) {
            throw badRequest(exception.getMessage());
        }
    }

    @PostMapping(value = "/analyze/stream", produces = "application/x-ndjson")
    public StreamingResponseBody analyzeStream(@RequestBody AttributionRequest request) {
        return output -> streamAnalysis(output, request);
    }

    private void streamAnalysis(OutputStream output, AttributionRequest sourceRequest) throws IOException {
        Object writeLock = new Object();
        try (ProcessAuditLog.AuditScope scope = auditLog.start("attribution.analyze.stream", Map.of(
                "userId", sourceRequest == null || sourceRequest.userId() == null ? "" : sourceRequest.userId(),
                "conversationId", sourceRequest == null || sourceRequest.conversationId() == null
                        ? "" : sourceRequest.conversationId(),
                "request", sourceRequest == null ? "" : sourceRequest))) {
            AttributionResponse response = executionService.execute(sourceRequest,
                    event -> writeStreamItem(output, writeLock,
                            new AttributionStreamItem("event", event, null, null))).response();
            scope.completed(Map.of(
                    "status", response.status(), "queryCount", response.queryCount(), "report", response.report(),
                    "conversationOutcome", reportGenerated(response) ? "success" : "failed"));
            writeStreamItem(output, writeLock, new AttributionStreamItem("result", null, response, null));
        } catch (Exception exception) {
            String message = rootMessage(exception);
            auditLog.event("attribution.analyze.stream.failed", Map.of(
                    "message", message, "exception", exception.getClass().getSimpleName()));
            writeStreamItem(output, writeLock, new AttributionStreamItem(
                    "error", new WorkflowEvent("workflow", "归因分析", "FAILED", message, null, null),
                    null, message));
        }
    }

    @GetMapping("/metadata")
    public AttributionMetadata metadata() {
        return new AttributionMetadata(
                AttributionCatalog.metricIds().stream()
                        .map(id -> new AttributionMetric(id, AttributionCatalog.metricName(id)))
                        .toList(),
                AttributionCatalog.dimensions(),
                new AttributionLimits(
                        2, 3, 8, 12, 5, 10,
                        policy.defaultMaxBranches(), policy.hardMaxBranches(),
                        policy.minAlignedContributionRate(), policy.reservedQueries()));
    }

    private void writeStreamItem(OutputStream output, Object writeLock, AttributionStreamItem item) {
        synchronized (writeLock) {
            try {
                output.write((objectMapper.writeValueAsString(item) + "\n").getBytes(StandardCharsets.UTF_8));
                output.flush();
            } catch (IOException exception) {
                throw new IllegalStateException("归因流式响应写入失败", exception);
            }
        }
    }

    private String rootMessage(Exception exception) {
        Throwable current = exception;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? "归因分析执行失败" : current.getMessage();
    }

    private boolean reportGenerated(AttributionResponse response) {
        return "completed".equals(response.status())
                && response.report() != null
                && response.report().summary() != null
                && !response.report().summary().isBlank();
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record AttributionMetric(String id, String name) {
    }

    public record AttributionLimits(
            int defaultMaxDepth,
            int hardMaxDepth,
            int defaultMaxQueries,
            int hardMaxQueries,
            int defaultTopN,
            int hardTopN,
            int defaultMaxBranches,
            int hardMaxBranches,
            java.math.BigDecimal minAlignedContributionRate,
            int reservedQueries) {
    }

    public record AttributionMetadata(
            List<AttributionMetric> metrics,
            List<AttributionDimension> dimensions,
            AttributionLimits limits) {
    }

    public record AttributionStreamItem(
            String type, WorkflowEvent event, AttributionResponse result, String message) {
    }
}
