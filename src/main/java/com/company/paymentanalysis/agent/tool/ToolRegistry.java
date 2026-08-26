package com.company.paymentanalysis.agent.tool;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Immutable startup-time registry. Concrete tools are added independently. */
@Service
public class ToolRegistry {

    private final Map<String, AgentTool> byId;
    private final List<ToolDescriptor> descriptors;

    public ToolRegistry(List<AgentTool> tools) {
        Map<String, AgentTool> index = new LinkedHashMap<>();
        for (AgentTool tool : tools) {
            if (tool == null || tool.descriptor() == null) {
                throw new IllegalStateException("Agent Tool 及其描述不能为空");
            }
            String toolId = tool.descriptor().toolId();
            AgentTool duplicate = index.putIfAbsent(toolId, tool);
            if (duplicate != null) {
                throw new IllegalStateException("重复注册 Agent Tool ID：" + toolId);
            }
        }
        this.byId = Map.copyOf(index);
        this.descriptors = index.values().stream()
                .map(AgentTool::descriptor)
                .sorted(Comparator.comparing(ToolDescriptor::toolId))
                .toList();
    }

    public AgentTool require(String toolId) {
        return find(toolId).orElseThrow(() ->
                new IllegalArgumentException("当前不支持 Tool：" + toolId));
    }

    public Optional<AgentTool> find(String toolId) {
        String normalized = toolId == null ? "" : toolId.trim().toLowerCase();
        return Optional.ofNullable(byId.get(normalized));
    }

    public List<ToolDescriptor> descriptors() {
        return descriptors;
    }
}
