package cn.lypi.agent.compact;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.AttachmentContentBlock;
import cn.lypi.contracts.context.ContentBlock;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.ToolCallContentBlock;
import cn.lypi.contracts.context.ToolResultContentBlock;
import cn.lypi.contracts.session.CompactionPlan;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.SessionEntry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 规划 compact 后需要重新注入的 read tool 状态。
 *
 * NOTE: 只恢复 compact 丢失段中已经由 read 工具披露过的内容；系统资源、
 * 规则文件和保留尾部已经存在的 read 结果不得重复回注。
 */
final class CompactResourceBackfillPlanner {
    private static final int MAX_ATTACHMENT_CHARS = 20_000;
    private static final int MAX_READ_STATES = 8;
    private static final String TRUNCATION_NOTICE = "\n\n[内容已截断；如需完整内容，请重新读取对应文件。]";

    private final Clock clock;

    CompactResourceBackfillPlanner(Clock clock) {
        this.clock = clock;
    }

    Optional<MessageEntry> plan(
        List<SessionEntry> branchEntries,
        CompactionPlan plan,
        String compactionEntryId,
        Instant timestamp
    ) {
        List<ReadState> readStates = readStatesToBackfill(branchEntries, plan);
        if (readStates.isEmpty()) {
            return Optional.empty();
        }

        String text = truncate(render(readStates), MAX_ATTACHMENT_CHARS);
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
                metadata(readStates)
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

    private List<ReadState> readStatesToBackfill(List<SessionEntry> branchEntries, CompactionPlan plan) {
        if (branchEntries == null || branchEntries.isEmpty() || plan == null) {
            return List.of();
        }
        List<SessionEntry> droppedEntries = droppedEntries(branchEntries, plan);
        if (droppedEntries.isEmpty()) {
            return List.of();
        }
        Set<String> keptReadPaths = readPaths(keptEntries(branchEntries, plan.firstKeptEntryId()));
        Map<String, String> readPathsByToolUseId = new HashMap<>();
        LinkedHashMap<String, ReadState> statesByPath = new LinkedHashMap<>();

        for (SessionEntry entry : droppedEntries) {
            if (!(entry instanceof MessageEntry messageEntry)) {
                continue;
            }
            AgentMessage message = messageEntry.message();
            for (ContentBlock block : safeContent(message)) {
                if (block instanceof ToolCallContentBlock toolCall) {
                    readPath(toolCall).ifPresent(path ->
                        readPathsByToolUseId.put(toolCall.toolUseId(), path)
                    );
                } else if (block instanceof ToolResultContentBlock toolResult && !toolResult.error()) {
                    String path = readPathsByToolUseId.get(toolResult.toolUseId());
                    if (path == null || keptReadPaths.contains(path) || isSystemResource(path)) {
                        continue;
                    }
                    statesByPath.remove(path);
                    statesByPath.put(path, new ReadState(path, safeText(toolResult.text())));
                }
            }
        }

        List<ReadState> states = new ArrayList<>(statesByPath.values());
        int fromIndex = Math.max(0, states.size() - MAX_READ_STATES);
        return List.copyOf(states.subList(fromIndex, states.size()));
    }

    private List<SessionEntry> droppedEntries(List<SessionEntry> branchEntries, CompactionPlan plan) {
        Set<String> summarizedIds = new HashSet<>(safeList(plan.summarizedEntryIds()));
        if (summarizedIds.isEmpty()) {
            return List.of();
        }
        return branchEntries.stream()
            .filter(entry -> summarizedIds.contains(entry.id()))
            .toList();
    }

    private List<SessionEntry> keptEntries(List<SessionEntry> branchEntries, String firstKeptEntryId) {
        if (firstKeptEntryId == null || firstKeptEntryId.isBlank()) {
            return List.of();
        }
        List<SessionEntry> kept = new ArrayList<>();
        boolean keep = false;
        for (SessionEntry entry : branchEntries) {
            if (entry.id().equals(firstKeptEntryId)) {
                keep = true;
            }
            if (keep) {
                kept.add(entry);
            }
        }
        return List.copyOf(kept);
    }

    private Set<String> readPaths(List<SessionEntry> entries) {
        Set<String> paths = new HashSet<>();
        Map<String, String> readPathsByToolUseId = new HashMap<>();
        for (SessionEntry entry : entries) {
            if (!(entry instanceof MessageEntry messageEntry)) {
                continue;
            }
            for (ContentBlock block : safeContent(messageEntry.message())) {
                if (block instanceof ToolCallContentBlock toolCall) {
                    readPath(toolCall).ifPresent(path ->
                        readPathsByToolUseId.put(toolCall.toolUseId(), path)
                    );
                } else if (block instanceof ToolResultContentBlock toolResult && !toolResult.error()) {
                    Optional.ofNullable(readPathsByToolUseId.get(toolResult.toolUseId())).ifPresent(paths::add);
                }
            }
        }
        return Set.copyOf(paths);
    }

    private Optional<String> readPath(ToolCallContentBlock toolCall) {
        if (toolCall == null || !"read".equals(normalizeToolName(toolCall.toolName()))) {
            return Optional.empty();
        }
        Object input = safeMap(toolCall.metadata()).get("input");
        if (!(input instanceof Map<?, ?> inputMap)) {
            return Optional.empty();
        }
        Object path = inputMap.get("path");
        if (path == null || path.toString().isBlank()) {
            return Optional.empty();
        }
        String normalizedPath = normalizePath(path.toString());
        if (normalizedPath.isBlank() || isSystemResource(normalizedPath)) {
            return Optional.empty();
        }
        return Optional.of(normalizedPath);
    }

    private String render(List<ReadState> states) {
        StringBuilder text = new StringBuilder();
        text.append("Post-compact read state has been restored.");
        text.append("\n\n## Restored Read Files\n");
        for (ReadState state : states) {
            text.append("\n### ").append(state.path()).append('\n');
            text.append(safeText(state.text()).strip()).append('\n');
        }
        return text.toString().strip();
    }

    private Map<String, Object> metadata(List<ReadState> states) {
        return Map.of(
            "readStateCount",
            states.size(),
            "paths",
            states.stream().map(ReadState::path).toList()
        );
    }

    private boolean isSystemResource(String path) {
        String normalized = normalizePath(path).toLowerCase(Locale.ROOT);
        String fileName = normalized.substring(normalized.lastIndexOf('/') + 1);
        return fileName.equals("agents.md")
            || fileName.equals("claude.md")
            || fileName.equals("claude.local.md")
            || fileName.endsWith(".rules")
            || fileName.endsWith(".rules.md")
            || normalized.startsWith(".codex/")
            || normalized.contains("/.codex/")
            || normalized.startsWith(".ly-pi/skills/")
            || normalized.contains("/.ly-pi/skills/")
            || normalized.startsWith(".claude/")
            || normalized.contains("/.claude/");
    }

    private String normalizePath(String path) {
        String normalized = safeText(path).replace('\\', '/').strip();
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private String truncate(String text, int maxChars) {
        String safeText = safeText(text);
        if (safeText.length() <= maxChars) {
            return safeText;
        }
        int prefixChars = Math.max(0, maxChars - TRUNCATION_NOTICE.length());
        return safeText.substring(0, prefixChars) + TRUNCATION_NOTICE;
    }

    private List<ContentBlock> safeContent(AgentMessage message) {
        return message == null || message.content() == null ? List.of() : message.content();
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private Map<String, Object> safeMap(Map<String, Object> values) {
        return values == null ? Map.of() : values;
    }

    private String safeText(String text) {
        return text == null ? "" : text;
    }

    private String normalizeToolName(String toolName) {
        return toolName == null ? "" : toolName.toLowerCase(Locale.ROOT);
    }

    private record ReadState(String path, String text) {}
}
