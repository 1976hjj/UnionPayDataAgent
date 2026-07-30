package com.company.paymentanalysis.controller;

import com.company.paymentanalysis.chat.ChatConversationMemoryService;
import com.company.paymentanalysis.chat.ChatConversationMemoryService.ChatMemoryUnavailableException;
import com.company.paymentanalysis.chat.ChatQueryWorkflowService;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient.LlmResultMessage;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/chat")
public class ChatQueryController {

    private final ChatQueryWorkflowService workflowService;
    private final ChatConversationMemoryService memoryService;

    public ChatQueryController(
            ChatQueryWorkflowService workflowService, ChatConversationMemoryService memoryService) {
        this.workflowService = workflowService;
        this.memoryService = memoryService;
    }

    @PostMapping("/query")
    public ChatResponse query(@RequestBody ChatRequest request) {
        String message = request == null || request.message() == null ? "" : request.message().trim();
        if (message.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "查询内容不能为空");
        }
        if (message.length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "查询内容不能超过200字");
        }
        String userId = identifier(request.userId(), "demo-user");
        String conversationId = identifier(request.sessionId(), UUID.randomUUID().toString());
        QueryContext restoredContext = request.context();
        try {
            restoredContext = memoryService.restoreContext(userId, conversationId).orElse(request.context());
        } catch (ChatMemoryUnavailableException ignored) {
            // Redis 不可用时仍允许当前浏览器继续查数，但不伪造持久化记忆。
        }
        ChatResponse response =
                workflowService.query(new ChatRequest(userId, conversationId, message, restoredContext));
        try {
            memoryService.saveTurn(userId, conversationId, message, response);
        } catch (ChatMemoryUnavailableException ignored) {
            // 依赖状态接口会向前端报告 Redis 故障。
        }
        return response;
    }

    @GetMapping("/conversations")
    public List<ConversationSummary> conversations(@RequestParam(defaultValue = "demo-user") String userId) {
        try {
            return memoryService.list(identifier(userId, "demo-user"));
        } catch (ChatMemoryUnavailableException exception) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Redis 不可用，无法读取历史会话");
        }
    }

    @GetMapping("/conversations/{conversationId}")
    public ConversationDetail conversation(
            @PathVariable String conversationId, @RequestParam(defaultValue = "demo-user") String userId) {
        String safeUserId = identifier(userId, "demo-user");
        String safeConversationId = identifier(conversationId, "");
        try {
            return memoryService.detail(safeUserId, safeConversationId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "对话不存在"));
        } catch (ChatMemoryUnavailableException exception) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "Redis 不可用，无法恢复历史会话");
        }
    }

    @GetMapping("/memory/status")
    public MemoryStatus memoryStatus() {
        return memoryService.status();
    }

    private String identifier(String value, String fallback) {
        String normalized = value == null || value.isBlank() ? fallback : value.trim();
        if (normalized.isBlank()
                || normalized.length() > 80
                || !normalized.matches("[A-Za-z0-9._-]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户或对话标识无效");
        }
        return normalized;
    }

    public record ChatRequest(String userId, String sessionId, String message, QueryContext context)
            implements Serializable {
    }

    public record ChatResponse(
            String status, String reply, List<String> suggestions, QueryContext context, QueryResult result,
            String executionEngine, List<WorkflowStep> workflowSteps, ChatQueryPlan queryPlan,
            String conversationId, LlmResultMessage llmMessage) implements Serializable {
    }

    public record QueryContext(
            String startDate, String endDate, String periodLabel,
            List<String> metricIds, List<String> dimensionIds) implements Serializable {

        public static QueryContext empty() {
            return new QueryContext("", "", "", List.of(), List.of());
        }

        public boolean hasPeriod() {
            return !startDate.isBlank() && !endDate.isBlank();
        }

        @JsonIgnore
        public boolean isEmpty() {
            return !hasPeriod() && metricIds.isEmpty() && dimensionIds.isEmpty();
        }
    }

    public record QueryResult(String summary, List<ResultColumn> columns, List<Map<String, String>> rows)
            implements Serializable {
    }

    public record ResultColumn(String id, String name, boolean numeric) implements Serializable {
    }

    public record WorkflowStep(String node, String name, String status, String detail) implements Serializable {
    }

    public record ChatQueryPlan(
            String dataSource, String dataSetId, List<String> rows,
            List<String> columns, List<QueryFilter> filters, String sqlPreview) implements Serializable {
    }

    public record QueryFilter(String name, String operation, List<String> values) implements Serializable {
    }

    public record ConversationSummary(String conversationId, String title, String updatedAt, int messageCount)
            implements Serializable {
    }

    public record ConversationDetail(
            String conversationId, String title, String createdAt, String updatedAt,
            QueryContext context, List<ConversationMessage> messages) implements Serializable {
    }

    public record ConversationMessage(
            int id, String role, String text, List<String> suggestions, QueryResult result,
            String executionEngine, List<WorkflowStep> workflowSteps, ChatQueryPlan queryPlan,
            String tone, LlmResultMessage llmMessage)
            implements Serializable {
    }

    public record MemoryStatus(
            String backend, boolean redisConfigured, boolean available, String detail, int ttlDays)
            implements Serializable {
    }
}
