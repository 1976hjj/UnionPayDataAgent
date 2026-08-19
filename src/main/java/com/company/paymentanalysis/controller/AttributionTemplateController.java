package com.company.paymentanalysis.controller;

import com.company.paymentanalysis.attribution.AttributionTemplateInterpreter;
import com.company.paymentanalysis.audit.ProcessAuditLog;
import com.company.paymentanalysis.attribution.AttributionTemplateModels.DimensionTemplate;
import com.company.paymentanalysis.attribution.AttributionTemplateModels.TemplateConfirmRequest;
import com.company.paymentanalysis.attribution.AttributionTemplateModels.TemplateChatRequest;
import com.company.paymentanalysis.attribution.AttributionTemplateModels.TemplateChatResponse;
import com.company.paymentanalysis.attribution.AttributionTemplateModels.TemplateConversationState;
import com.company.paymentanalysis.attribution.AttributionTemplateModels.TemplateStateRequest;
import com.company.paymentanalysis.chat.AttributionConversationRouterService;
import com.company.paymentanalysis.chat.ChatConversationMemoryService;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/attribution/template")
public class AttributionTemplateController {

    private final AttributionTemplateInterpreter interpreter;
    private final AttributionConversationRouterService conversationRouter;
    private final ChatConversationMemoryService memoryService;
    private final OpenAiCompatibleLlmClient llmClient;
    private final ProcessAuditLog auditLog;

    public AttributionTemplateController(
            AttributionTemplateInterpreter interpreter,
            AttributionConversationRouterService conversationRouter,
            ChatConversationMemoryService memoryService,
            OpenAiCompatibleLlmClient llmClient,
            ProcessAuditLog auditLog) {
        this.interpreter = interpreter;
        this.conversationRouter = conversationRouter;
        this.memoryService = memoryService;
        this.llmClient = llmClient;
        this.auditLog = auditLog;
    }

    @PostMapping("/chat")
    public TemplateChatResponse chat(@RequestBody TemplateChatRequest request) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "对话内容不能为空");
        }
        if (request.message().length() > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "对话内容不能超过2000字");
        }
        try {
            String model = llmClient.resolveSelection(request.model());
            try (ProcessAuditLog.AuditScope scope = auditLog.start("attribution.template.chat", Map.of(
                    "userId", request.userId() == null ? "" : request.userId(),
                    "conversationId", request.conversationId() == null ? "" : request.conversationId(),
                    "userInput", request.message().trim(),
                    "model", model,
                    "currentTemplate", request.currentTemplate() == null ? DimensionTemplate.auto() : request.currentTemplate()))) {
                TemplateChatResponse response = conversationRouter.respond(new TemplateChatRequest(
                        request.userId(), request.conversationId(), request.message().trim(),
                        request.conversationHistory(), request.currentTemplate(), model));
                scope.completed(Map.of(
                        "status", response.status(), "assistantReply", response.reply(),
                        "template", response.template() == null ? DimensionTemplate.auto() : response.template(),
                        "conversationOutcome", "failed",
                        "outcomeReason", "Template chat does not produce an attribution report"));
                return response;
            }
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @PostMapping("/confirm")
    public DimensionTemplate confirm(@RequestBody TemplateConfirmRequest request) {
        try {
            if (request == null || request.template() == null) {
                throw new IllegalArgumentException("归因模板不能为空");
            }
            DimensionTemplate confirmed = interpreter.confirm(request.template());
            if (request.userId() != null && !request.userId().isBlank()
                    && request.conversationId() != null && !request.conversationId().isBlank()) {
                memoryService.saveAttributionState(
                        request.userId().trim(), request.conversationId().trim(),
                        new TemplateConversationState("READY_TO_CONFIRM", confirmed, List.of(), List.of()));
            }
            return confirmed;
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @PostMapping("/state")
    public void saveState(@RequestBody TemplateStateRequest request) {
        if (request == null || request.state() == null
                || request.userId() == null || request.userId().isBlank()
                || request.conversationId() == null || request.conversationId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "会话与模板状态不能为空");
        }
        memoryService.saveAttributionState(
                request.userId().trim(), request.conversationId().trim(), request.state());
    }
}
