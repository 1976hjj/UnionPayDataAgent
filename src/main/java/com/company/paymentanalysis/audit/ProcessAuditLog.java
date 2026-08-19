package com.company.paymentanalysis.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Writes one JSON object per line so that a complete user operation can be found by traceId.
 * It is deliberately separate from normal application logs: audit write failures must not
 * interrupt a data query or attribution run.
 */
@Component
public class ProcessAuditLog {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessAuditLog.class);
    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

    private final ObjectMapper objectMapper;
    private final ProcessAuditProperties properties;
    private final ReentrantLock writeLock = new ReentrantLock();

    public ProcessAuditLog(ObjectMapper objectMapper, ProcessAuditProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public AuditScope start(String operation, Map<String, ?> details) {
        String previousTraceId = TRACE_ID.get();
        String traceId = UUID.randomUUID().toString();
        TRACE_ID.set(traceId);
        event("operation.started", merge(Map.of("operation", operation), details));
        return new AuditScope(previousTraceId, operation);
    }

    public void event(String event, Map<String, ?> details) {
        if (!properties.enabled()) return;
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("timestamp", Instant.now().toString());
        entry.put("traceId", traceId());
        entry.put("event", event);
        entry.put("details", sanitize(details));
        append(entry);
    }

    public String traceId() {
        String traceId = TRACE_ID.get();
        return traceId == null ? "unscoped-" + UUID.randomUUID() : traceId;
    }

    private Map<String, Object> merge(Map<String, ?> left, Map<String, ?> right) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (left != null) merged.putAll(left);
        if (right != null) merged.putAll(right);
        return merged;
    }

    private Object sanitize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                String name = String.valueOf(key);
                copy.put(name, sensitive(name) ? "[REDACTED]" : sanitize(item));
            });
            return copy;
        }
        if (value instanceof Iterable<?> values) {
            java.util.List<Object> copy = new java.util.ArrayList<>();
            values.forEach(item -> copy.add(sanitize(item)));
            return copy;
        }
        if (value instanceof String text) {
            return text.length() <= properties.maxTextLength()
                    ? text
                    : text.substring(0, properties.maxTextLength())
                            + "... [truncated by process-audit.max-text-length]";
        }
        return value;
    }

    private boolean sensitive(String name) {
        String normalized = name.toLowerCase(java.util.Locale.ROOT).replace("-", "");
        return normalized.contains("password") || normalized.contains("authorization")
                || normalized.contains("apikey") || normalized.equals("token")
                || normalized.endsWith("token") || normalized.contains("cookie")
                || normalized.contains("secret");
    }

    private void append(Map<String, Object> entry) {
        try {
            String line = objectMapper.writeValueAsString(entry) + System.lineSeparator();
            Path target = Path.of(properties.file()).toAbsolutePath().normalize();
            Path parent = target.getParent();
            writeLock.lock();
            try {
                if (parent != null) Files.createDirectories(parent);
                Files.writeString(target, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            } finally {
                writeLock.unlock();
            }
        } catch (IOException exception) {
            LOGGER.warn("Unable to append process audit event; application flow will continue", exception);
        }
    }

    public final class AuditScope implements AutoCloseable {
        private final String previousTraceId;
        private final String operation;
        private boolean closed;
        private boolean finalOutcomeRecorded;

        private AuditScope(String previousTraceId, String operation) {
            this.previousTraceId = previousTraceId;
            this.operation = operation;
        }

        public void completed(Map<String, ?> details) {
            event("operation.completed", merge(Map.of("operation", operation), details));
            finalOutcomeRecorded = true;
        }

        public void failed(Exception exception) {
            event("operation.failed", Map.of(
                    "operation", operation,
                    "conversationOutcome", "failed",
                    "exception", exception.getClass().getSimpleName(),
                    "message", exception.getMessage() == null ? "" : exception.getMessage()));
            finalOutcomeRecorded = true;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (!finalOutcomeRecorded) {
                event("operation.failed", Map.of(
                        "operation", operation,
                        "conversationOutcome", "failed",
                        "reason", "Operation ended without a successful completion record"));
            }
            if (previousTraceId == null) TRACE_ID.remove();
            else TRACE_ID.set(previousTraceId);
        }
    }
}
