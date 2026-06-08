package cn.lypi.agent.compact;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.AttachmentContentBlock;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.mcp.McpServerConfig;
import cn.lypi.contracts.prompt.PromptParameter;
import cn.lypi.contracts.prompt.PromptTemplate;
import cn.lypi.contracts.resource.ContextFile;
import cn.lypi.contracts.resource.MemorySource;
import cn.lypi.contracts.resource.ResourceDiagnostic;
import cn.lypi.contracts.resource.ResourceSnapshot;
import cn.lypi.contracts.session.CompactionEntry;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.skill.SkillDescriptor;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 规划 compact 后需要重新注入的资源上下文消息。
 *
 * NOTE: 仅回注 ResourceSnapshot 已经披露的内容；Skill 正文必须等激活记录进入
 * session 后再按已调用 Skill 回注，不能在 compact 时一次性加载全部正文。
 */
final class CompactResourceBackfillPlanner {
    private static final int MAX_ATTACHMENT_CHARS = 20_000;
    private static final int MAX_CONTEXT_FILE_CHARS = 5_000;
    private static final String TRUNCATION_NOTICE = "\n\n[内容已截断；如需完整内容，请重新读取对应资源。]";

    private final Clock clock;

    CompactResourceBackfillPlanner(Clock clock) {
        this.clock = clock;
    }

    Optional<MessageEntry> plan(ResourceSnapshot resources, String compactionEntryId, Instant timestamp) {
        if (resources == null || isEmpty(resources)) {
            return Optional.empty();
        }

        String text = truncate(render(resources), MAX_ATTACHMENT_CHARS);
        if (text.isBlank()) {
            return Optional.empty();
        }

        Instant safeTimestamp = Optional.ofNullable(timestamp).orElseGet(clock::instant);
        String attachmentId = "compact-resource-" + compactionEntryId;
        AgentMessage message = new AgentMessage(
            "msg-" + attachmentId + "-" + UUID.randomUUID(),
            MessageRole.SYSTEM_LOCAL,
            MessageKind.ATTACHMENT,
            List.of(new AttachmentContentBlock(
                attachmentId,
                text,
                "text/markdown",
                metadata(resources)
            )),
            safeTimestamp,
            Optional.empty(),
            Optional.empty()
        );
        return Optional.of(new MessageEntry(
            "entry-" + attachmentId + "-" + UUID.randomUUID(),
            compactionEntryId,
            message,
            safeTimestamp
        ));
    }

    private boolean isEmpty(ResourceSnapshot resources) {
        return agentFiles(resources).isEmpty()
            && memorySources(resources).isEmpty()
            && skills(resources).isEmpty()
            && promptTemplates(resources).isEmpty()
            && mcpServers(resources).isEmpty();
    }

    private String render(ResourceSnapshot resources) {
        StringBuilder text = new StringBuilder();
        text.append("Post-compact resource context has been restored.\n");
        appendContextFiles(text, agentFiles(resources));
        appendMemorySources(text, memorySources(resources));
        appendSkills(text, skills(resources));
        appendPromptTemplates(text, promptTemplates(resources));
        appendMcpServers(text, mcpServers(resources));
        return text.toString().strip();
    }

    private void appendContextFiles(StringBuilder text, List<ContextFile> files) {
        if (files.isEmpty()) {
            return;
        }
        text.append("\n\n## Restored Context Files\n");
        for (ContextFile file : files) {
            text.append("\n### ").append(file.path()).append('\n');
            text.append(truncate(safeText(file.content()).strip(), MAX_CONTEXT_FILE_CHARS)).append('\n');
        }
    }

    private void appendMemorySources(StringBuilder text, List<MemorySource> memorySources) {
        if (memorySources.isEmpty()) {
            return;
        }
        text.append("\n\n## Memory Sources\n");
        for (MemorySource memorySource : memorySources) {
            text.append("- ")
                .append(memorySource.path())
                .append(" (")
                .append(memorySource.contentHash())
                .append(")\n");
        }
    }

    private void appendSkills(StringBuilder text, List<SkillDescriptor> skills) {
        if (skills.isEmpty()) {
            return;
        }
        text.append("\n\n## Available Skills\n");
        for (SkillDescriptor skill : skills) {
            text.append("- skill:")
                .append(skill.name())
                .append(" source=")
                .append(skill.source())
                .append(" hash=")
                .append(skill.contentHash())
                .append('\n')
                .append("  description: ")
                .append(skill.description())
                .append('\n');
            if (!safeList(skill.pathGlobs()).isEmpty()) {
                text.append("  paths: ").append(skill.pathGlobs()).append('\n');
            }
            if (!safeList(skill.allowedTools()).isEmpty()) {
                text.append("  allowedTools: ").append(skill.allowedTools()).append('\n');
            }
        }
    }

    private void appendPromptTemplates(StringBuilder text, List<PromptTemplate> promptTemplates) {
        if (promptTemplates.isEmpty()) {
            return;
        }
        text.append("\n\n## Prompt Templates\n");
        for (PromptTemplate template : promptTemplates) {
            text.append("- prompt:")
                .append(template.name())
                .append(" source=")
                .append(template.source())
                .append(" hash=")
                .append(template.contentHash())
                .append('\n')
                .append("  description: ")
                .append(template.description())
                .append('\n');
            if (!safeList(template.parameters()).isEmpty()) {
                text.append("  parameters: ").append(parameterSummary(template.parameters())).append('\n');
            }
            if (template.templateBody() != null && !template.templateBody().isBlank()) {
                text.append("  body:\n")
                    .append(truncate(template.templateBody().strip(), MAX_CONTEXT_FILE_CHARS))
                    .append('\n');
            }
        }
    }

    private void appendMcpServers(StringBuilder text, List<McpServerConfig> mcpServers) {
        if (mcpServers.isEmpty()) {
            return;
        }
        text.append("\n\n## MCP Servers\n");
        mcpServers.forEach(server ->
            text.append("- mcp:")
                .append(server.name())
                .append(" transport=")
                .append(server.transport())
                .append('\n')
        );
    }

    private String truncate(String text, int maxChars) {
        String safeText = safeText(text);
        if (safeText.length() <= maxChars) {
            return safeText;
        }
        int prefixChars = Math.max(0, maxChars - TRUNCATION_NOTICE.length());
        return safeText.substring(0, prefixChars) + TRUNCATION_NOTICE;
    }

    private String parameterSummary(List<PromptParameter> parameters) {
        return safeList(parameters).stream()
            .map(parameter -> parameter.name() + (parameter.required() ? "(required)" : "(optional)"))
            .reduce((left, right) -> left + ", " + right)
            .orElse("");
    }

    private Map<String, Object> metadata(ResourceSnapshot resources) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("agentFileCount", agentFiles(resources).size());
        metadata.put("memorySourceCount", memorySources(resources).size());
        metadata.put("skillCount", skills(resources).size());
        metadata.put("promptTemplateCount", promptTemplates(resources).size());
        metadata.put("mcpServerCount", mcpServers(resources).size());
        metadata.put("diagnosticCount", diagnostics(resources).size());
        return Map.copyOf(metadata);
    }

    private List<ContextFile> agentFiles(ResourceSnapshot resources) {
        return safeList(resources.agentFiles());
    }

    private List<MemorySource> memorySources(ResourceSnapshot resources) {
        return safeList(resources.memorySources());
    }

    private List<SkillDescriptor> skills(ResourceSnapshot resources) {
        if (resources.skillIndex() == null) {
            return List.of();
        }
        return safeList(resources.skillIndex().skills());
    }

    private List<PromptTemplate> promptTemplates(ResourceSnapshot resources) {
        return safeList(resources.promptTemplates());
    }

    private List<McpServerConfig> mcpServers(ResourceSnapshot resources) {
        return safeList(resources.mcpServers());
    }

    private List<ResourceDiagnostic> diagnostics(ResourceSnapshot resources) {
        return safeList(resources.diagnostics());
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private String safeText(String text) {
        return text == null ? "" : text;
    }
}
