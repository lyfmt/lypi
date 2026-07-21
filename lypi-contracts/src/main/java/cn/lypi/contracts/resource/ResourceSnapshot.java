package cn.lypi.contracts.resource;

import cn.lypi.contracts.mcp.McpServerConfig;
import cn.lypi.contracts.memory.MemoryScope;
import cn.lypi.contracts.prompt.PromptTemplate;
import cn.lypi.contracts.skill.SkillIndex;
import cn.lypi.contracts.subagent.ExpertAgentDefinition;
import java.util.List;

public record ResourceSnapshot(
    List<ContextFile> agentFiles,
    List<MemorySource> memorySources,
    SkillIndex skillIndex,
    List<PromptTemplate> promptTemplates,
    List<McpServerConfig> mcpServers,
    List<ExpertAgentDefinition> expertAgents,
    List<ResourceDiagnostic> diagnostics
) {
    public ResourceSnapshot {
        agentFiles = agentFiles == null ? List.of() : List.copyOf(agentFiles);
        memorySources = memorySources == null ? List.of() : List.copyOf(memorySources);
        promptTemplates = promptTemplates == null ? List.of() : List.copyOf(promptTemplates);
        mcpServers = mcpServers == null ? List.of() : List.copyOf(mcpServers);
        expertAgents = expertAgents == null ? List.of() : List.copyOf(expertAgents);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    public ResourceSnapshot(
        List<ContextFile> agentFiles,
        List<MemorySource> memorySources,
        SkillIndex skillIndex,
        List<PromptTemplate> promptTemplates,
        List<McpServerConfig> mcpServers,
        List<ResourceDiagnostic> diagnostics
    ) {
        this(agentFiles, memorySources, skillIndex, promptTemplates, mcpServers, List.of(), diagnostics);
    }
}
