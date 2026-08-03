package com.company.paymentanalysis.controller;

import com.company.paymentanalysis.chat.ChatConversationMemoryService;
import com.company.paymentanalysis.controller.ChatQueryController.MemoryStatus;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient;
import com.company.paymentanalysis.llm.OpenAiCompatibleLlmClient.LlmHealth;
import com.company.paymentanalysis.smartbi.SmartBiClient;
import com.company.paymentanalysis.smartbi.SmartBiClient.SmartBiHealth;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public class SystemDependencyController {

    private final ChatConversationMemoryService memoryService;
    private final OpenAiCompatibleLlmClient llmClient;
    private final SmartBiClient smartBiClient;

    public SystemDependencyController(
            ChatConversationMemoryService memoryService, OpenAiCompatibleLlmClient llmClient,
            SmartBiClient smartBiClient) {
        this.memoryService = memoryService;
        this.llmClient = llmClient;
        this.smartBiClient = smartBiClient;
    }

    @GetMapping("/dependencies")
    public DependencyHealthResponse dependencies(@RequestParam(required = false) String model) {
        MemoryStatus redis = memoryService.status();
        LlmHealth llm = llmClient.health(model);
        SmartBiHealth smartBi = smartBiClient.health();
        List<DependencyStatus> dependencies = List.of(
                new DependencyStatus(
                        "redis", "Redis", redis.available() ? "UP" : "DOWN", redis.detail(), null),
                new DependencyStatus("llm", llm.name(), llm.status(), llm.detail(), llm.checkedAt()),
                new DependencyStatus(
                        "smartbi", smartBi.name(), smartBi.status(), smartBi.detail(), smartBi.checkedAt()));
        String overall =
                dependencies.stream().anyMatch(value -> "DOWN".equals(value.status())) ? "DEGRADED" : "UP";
        return new DependencyHealthResponse(overall, dependencies);
    }

    @GetMapping("/models")
    public ModelOptionsResponse models() {
        return new ModelOptionsResponse(llmClient.defaultModel(), llmClient.supportedModels());
    }

    public record DependencyHealthResponse(String overallStatus, List<DependencyStatus> dependencies) {
    }

    public record DependencyStatus(String code, String name, String status, String detail, String checkedAt) {
    }

    public record ModelOptionsResponse(String defaultModel, List<String> models) {
    }
}
