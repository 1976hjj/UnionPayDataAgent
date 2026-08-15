package com.company.paymentanalysis.controller;

import com.company.paymentanalysis.attribution.AttributionTemplateInterpreter;
import com.company.paymentanalysis.attribution.AttributionTemplateModels.DimensionTemplate;
import com.company.paymentanalysis.attribution.AttributionTemplateModels.TemplateChatRequest;
import com.company.paymentanalysis.attribution.AttributionTemplateModels.TemplateChatResponse;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient;
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
    private final OpenAiCompatibleLlmClient llmClient;

    public AttributionTemplateController(
            AttributionTemplateInterpreter interpreter, OpenAiCompatibleLlmClient llmClient) {
        this.interpreter = interpreter;
        this.llmClient = llmClient;
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
            return interpreter.interpret(new TemplateChatRequest(
                    request.userId(), request.conversationId(), request.message().trim(),
                    request.conversationHistory(), request.currentTemplate(), model));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @PostMapping("/confirm")
    public DimensionTemplate confirm(@RequestBody DimensionTemplate template) {
        try {
            return interpreter.confirm(template);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }
}
