package cn.lypi.agent.compact;

import cn.lypi.agent.ContextAssembly;
import cn.lypi.agent.ContextBuildRequest;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.AttachmentContentBlock;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.mcp.McpServerConfig;
import cn.lypi.contracts.resource.ResourceSnapshot;
import cn.lypi.contracts.runtime.CompactStateBackfillItem;
import cn.lypi.contracts.runtime.CompactStateBackfillPort;
import cn.lypi.contracts.runtime.CompactStateBackfillRequest;
import cn.lypi.contracts.session.CompactionPlan;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.skill.SkillMention;
import cn.lypi.contracts.tool.ToolDescriptor;
import cn.lypi.contracts.tool.ToolRegistrySnapshot;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 聚合 compact 后需要作为模型可见消息回填的状态。
 *
 * NOTE: 回填结果必须追加为 session MessageEntry，不能只存在于本次 decision context。
 */
final class CompactStateBackfillPlanner {
    private static final int MAX_RUNTIME_CHARS = 12_000;
    private static final int MAX_SKILL_CHARS = 20_000;
    private static final int MAX_SKILLS = 4;
    private static final int MAX_MCP_CHARS = 8_000;
    private static final String TRUNCATION_NOTICE = "\n\n[内容已截断；如需完整内容，请重新读取或查询对应状态。]";

    private final CompactResourceBackfillPlanner resourceBackfillPlanner;
    private final CompactStateBackfillPort stateBackfillPort;
    private final Clock clock;

    CompactStateBackfillPlanner(Clock clock, CompactStateBackfillPort stateBackfillPort) {
        this.clock = clock;
        this.resourceBackfillPlanner = new CompactResourceBackfillPlanner(clock);
        this.stateBackfillPort = stateBackfillPort == null ? CompactStateBackfillPort.none() : stateBackfillPort;
    }

    List<MessageEntry> plan(
        List<SessionEntry> branchEntries,
        CompactionPlan plan,
        String compactionEntryId,
        Instant timestamp,
        CompactionRequest request
    ) {
        Instant safeTimestamp = Optional.ofNullable(timestamp).orElseGet(clock::instant);
        List<MessageEntry> entries = new ArrayList<>();
        resourceBackfillPlanner.plan(branchEntries, plan, compactionEntryId, safeTimestamp).ifPresent(entries::add);
        entries.addAll(runtimeEntries(compactionEntryId, safeTimestamp, request));
        entries.addAll(skillEntries(compactionEntryId, safeTimestamp, request.contextBuildRequest()));
        mcpEntry(compactionEntryId, safeTimestamp, request.assembly(), request.tools()).ifPresent(entries::add);
        return relinkParents(entries, compactionEntryId);
    }

    private List<MessageEntry> runtimeEntries(String compactionEntryId, Instant timestamp, CompactionRequest request) {
        List<CompactStateBackfillItem> items;
        try {
            ContextAssembly assembly = request.assembly();
            ResourceSnapshot resources = assembly == null ? null : assembly.resources();
            ContextBuildRequest buildRequest = request.contextBuildRequest();
            List<SkillMention> mentions = buildRequest == null ? List.of() : safeList(buildRequest.skillMentions());
            items = stateBackfillPort.backfill(new CompactStateBackfillRequest(
                request.sessionId(),
                request.cwd(),
                resources,
                request.tools(),
                mentions
            ));
        } catch (RuntimeException exception) {
            return List.of();
        }
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<MessageEntry> entries = new ArrayList<>();
        for (CompactStateBackfillItem item : items) {
            if (item == null || safeText(item.content()).isBlank()) {
                continue;
            }
            String attachmentId = safeAttachmentId(item.attachmentId(), "compact-runtime-state-" + entries.size());
            entries.add(entry(
                compactionEntryId,
                attachmentId,
                truncate(renderRuntimeItem(item), MAX_RUNTIME_CHARS),
                metadata("runtime", item.metadata()),
                timestamp
            ));
        }
        return entries;
    }

    private List<MessageEntry> skillEntries(
        String compactionEntryId,
        Instant timestamp,
        ContextBuildRequest buildRequest
    ) {
        if (buildRequest == null || buildRequest.skillMentions() == null || buildRequest.skillMentions().isEmpty()) {
            return List.of();
        }
        List<MessageEntry> entries = new ArrayList<>();
        for (SkillMention mention : buildRequest.skillMentions()) {
            if (mention == null || entries.size() >= MAX_SKILLS) {
                continue;
            }
            String body;
            try {
                body = Files.readString(mention.skillFile()).strip();
            } catch (Exception exception) {
                continue;
            }
            if (body.isBlank()) {
                continue;
            }
            String text = "# Skill: " + mention.name() + "\n\nPath: " + mention.skillFile() + "\n\n" + body;
            entries.add(entry(
                compactionEntryId,
                "compact-skill-" + slug(mention.name()),
                truncate(text, MAX_SKILL_CHARS),
                Map.of("backfillType", "skill", "skillName", mention.name()),
                timestamp
            ));
        }
        return entries;
    }

    private Optional<MessageEntry> mcpEntry(
        String compactionEntryId,
        Instant timestamp,
        ContextAssembly assembly,
        ToolRegistrySnapshot tools
    ) {
        ResourceSnapshot resources = assembly == null ? null : assembly.resources();
        List<McpServerConfig> servers = resources == null || resources.mcpServers() == null
            ? List.of()
            : resources.mcpServers();
        List<ToolDescriptor> mcpTools = tools == null || tools.tools() == null
            ? List.of()
            : tools.tools().stream()
                .filter(tool -> tool != null && safeText(tool.name()).startsWith("mcp__"))
                .toList();
        if (servers.isEmpty() && mcpTools.isEmpty()) {
            return Optional.empty();
        }

        String text = renderMcpGuidance(servers, mcpTools);
        if (text.isBlank()) {
            return Optional.empty();
        }
        String sourceName = servers.isEmpty() ? "tools" : servers.getFirst().name();
        return Optional.of(entry(
            compactionEntryId,
            "compact-mcp-guidance-" + slug(sourceName),
            truncate(text, MAX_MCP_CHARS),
            Map.of("backfillType", "mcp", "serverCount", Integer.toString(servers.size()), "toolCount", Integer.toString(mcpTools.size())),
            timestamp
        ));
    }

    private String renderRuntimeItem(CompactStateBackfillItem item) {
        String title = safeText(item.title()).isBlank() ? item.attachmentId() : item.title();
        return "# " + title + "\n\n" + safeText(item.content()).strip();
    }

    private String renderMcpGuidance(List<McpServerConfig> servers, List<ToolDescriptor> tools) {
        StringBuilder text = new StringBuilder();
        text.append("# MCP Guidance\n\n");
        text.append("Compact restored MCP availability. Use the listed mcp__server__tool tools directly when needed; do not infer secrets or configuration values from compact context.");
        if (!servers.isEmpty()) {
            text.append("\n\n## Servers\n");
            for (McpServerConfig server : servers) {
                if (server == null) {
                    continue;
                }
                text.append("- ").append(safeText(server.name()));
                if (server.transport() != null) {
                    text.append(" (").append(server.transport().name()).append(')');
                }
                text.append('\n');
            }
        }
        if (!tools.isEmpty()) {
            text.append("\n## Tools\n");
            for (ToolDescriptor tool : tools) {
                text.append("- ").append(tool.name());
                if (!safeText(tool.description()).isBlank()) {
                    text.append(": ").append(tool.description());
                }
                text.append('\n');
            }
        }
        return text.toString().strip();
    }

    private List<MessageEntry> relinkParents(List<MessageEntry> entries, String firstParentId) {
        if (entries.isEmpty()) {
            return List.of();
        }
        List<MessageEntry> relinked = new ArrayList<>();
        String parentId = firstParentId;
        for (MessageEntry entry : entries) {
            MessageEntry next = new MessageEntry(entry.id(), parentId, entry.message(), entry.timestamp());
            relinked.add(next);
            parentId = next.id();
        }
        return List.copyOf(relinked);
    }

    private MessageEntry entry(
        String parentId,
        String attachmentId,
        String text,
        Map<String, Object> metadata,
        Instant timestamp
    ) {
        AgentMessage message = new AgentMessage(
            "msg-" + attachmentId + "-" + UUID.randomUUID(),
            MessageRole.SYSTEM_LOCAL,
            MessageKind.ATTACHMENT,
            List.of(new AttachmentContentBlock(
                attachmentId,
                text,
                "text/markdown",
                metadata
            )),
            timestamp,
            Optional.empty(),
            Optional.empty()
        );
        return new MessageEntry(
            "entry-" + attachmentId + "-" + UUID.randomUUID(),
            parentId,
            message,
            timestamp
        );
    }

    private Map<String, Object> metadata(String backfillType, Map<String, String> itemMetadata) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("backfillType", backfillType);
        if (itemMetadata != null) {
            metadata.putAll(itemMetadata);
        }
        return Map.copyOf(metadata);
    }

    private String safeAttachmentId(String attachmentId, String fallback) {
        String safe = safeText(attachmentId).strip();
        return safe.isBlank() ? fallback : safe;
    }

    private String truncate(String text, int maxChars) {
        String safe = safeText(text);
        if (safe.length() <= maxChars) {
            return safe;
        }
        int prefixChars = Math.max(0, maxChars - TRUNCATION_NOTICE.length());
        return safe.substring(0, prefixChars) + TRUNCATION_NOTICE;
    }

    private String slug(String value) {
        String normalized = safeText(value).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-");
        normalized = normalized.replaceAll("^-+", "").replaceAll("-+$", "");
        return normalized.isBlank() ? "unknown" : normalized;
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }
}
