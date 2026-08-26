package com.company.paymentanalysis.controller;

import com.company.paymentanalysis.agent.AgentEntryMode;
import com.company.paymentanalysis.agent.AgentOrchestrator;
import com.company.paymentanalysis.agent.AgentRequest;
import com.company.paymentanalysis.agent.AgentResponse;
import com.company.paymentanalysis.audit.ProcessAuditLog;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The single Agent entry point. Existing feature-specific endpoints remain unchanged in phase one. */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private static final int MAX_MESSAGE_LENGTH = 2000;

    private final AgentOrchestrator orchestrator;
    private final OpenAiCompatibleLlmClient llmClient;
    private final ProcessAuditLog auditLog;

    public AgentController(
            AgentOrchestrator orchestrator,
            OpenAiCompatibleLlmClient llmClient,
            ProcessAuditLog auditLog) {
        this.orchestrator = orchestrator;
        this.llmClient = llmClient;
        this.auditLog = auditLog;
    }

    @PostMapping("/chat")
    public AgentResponse chat(@RequestBody AgentRequest source) {
        if (source == null || source.message() == null || source.message().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "对话内容不能为空");
        }
        String message = source.message().trim();
        if (message.length() > MAX_MESSAGE_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "对话内容不能超过 2000 字");
        }
        String model;
        try {
            model = llmClient.resolveSelection(source.model());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
        AgentRequest request = new AgentRequest(
                identifier(source.userId(), "demo-user"),
                identifier(source.conversationId(), UUID.randomUUID().toString()),
                message,
                source.entryMode() == null ? AgentEntryMode.BI_CHAT : source.entryMode(),
                model,
                source.confirmed(),
                source.queryContext(),
                source.attributionTemplate(),
                source.attributionExecutionOptions(),
                source.visualizationOptions(),
                source.inputArtifactIds(),
                source.parameters(),
                source.action());
        try (ProcessAuditLog.AuditScope scope = auditLog.start("agent.chat", Map.of(
                "userId", request.userId(),
                "conversationId", request.conversationId(),
                "entryMode", request.entryMode().name(),
                "userInput", request.message(),
                "model", request.model(),
                "confirmed", request.confirmed(),
                "action", request.action().name()))) {
            try {
                AgentResponse response = orchestrator.respond(request);
                scope.completed(Map.of(
                        "status", response.status(),
                        "activeSkill", response.activeSkill(),
                        "viewType", response.viewModel().type(),
                        "conversationOutcome", "completed"));
                return response;
            } catch (IllegalArgumentException exception) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
            }
        }
    }

    private String identifier(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.trim();
        if (!normalized.matches("[A-Za-z0-9._:-]{1,120}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户或会话标识无效");
        }
        return normalized;
    }
}
