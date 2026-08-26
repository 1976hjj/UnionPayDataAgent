package com.company.paymentanalysis.controller;

import com.company.paymentanalysis.agent.SkillDescriptor;
import com.company.paymentanalysis.agent.SkillRegistry;
import com.company.paymentanalysis.agent.tool.ToolDescriptor;
import com.company.paymentanalysis.agent.tool.ToolRegistry;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent")
public class AgentCapabilityController {

    private final SkillRegistry skillRegistry;
    private final ToolRegistry toolRegistry;

    public AgentCapabilityController(SkillRegistry skillRegistry, ToolRegistry toolRegistry) {
        this.skillRegistry = skillRegistry;
        this.toolRegistry = toolRegistry;
    }

    @GetMapping("/capabilities")
    public CapabilityCatalog capabilities() {
        return new CapabilityCatalog(skillRegistry.descriptors(), toolRegistry.descriptors());
    }

    public record CapabilityCatalog(List<SkillDescriptor> skills, List<ToolDescriptor> tools) {
    }
}
