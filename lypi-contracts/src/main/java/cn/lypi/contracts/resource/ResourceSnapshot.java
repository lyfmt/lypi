package cn.lypi.contracts.resource;

import cn.lypi.contracts.mcp.McpServerConfig;
import cn.lypi.contracts.memory.MemoryScope;
import cn.lypi.contracts.prompt.PromptTemplate;
import cn.lypi.contracts.skill.SkillIndex;
import java.util.List;

public record ResourceSnapshot(
    List<ContextFile> agentFiles,
    List<MemorySource> memorySources,
    SkillIndex skillIndex,
    List<PromptTemplate> promptTemplates,
    List<McpServerConfig> mcpServers,
    List<ResourceDiagnostic> diagnostics
) {}

