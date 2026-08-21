package com.company.paymentanalysis.controller;

import com.company.paymentanalysis.attribution.AttributionTemplateModels.TemplateStateRequest;
import com.company.paymentanalysis.chat.ChatConversationMemoryService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/attribution/template")
public class AttributionTemplateController {

    private final ChatConversationMemoryService memoryService;

    public AttributionTemplateController(ChatConversationMemoryService memoryService) {
        this.memoryService = memoryService;
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
