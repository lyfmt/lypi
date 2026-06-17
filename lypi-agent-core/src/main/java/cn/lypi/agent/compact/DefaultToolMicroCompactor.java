package cn.lypi.agent.compact;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContentBlock;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.ToolCallContentBlock;
import cn.lypi.contracts.context.ToolResultContentBlock;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.tool.ToolDescriptor;
import cn.lypi.contracts.tool.ToolRegistrySnapshot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

public final class DefaultToolMicroCompactor implements ToolMicroCompactor {
    public static final String CLEARED_TOOL_RESULT_TEXT = "[Old tool result content cleared by micro compact]";
    private static final Duration HOT_WINDOW = Duration.ofMinutes(5);
    private static final int DEFAULT_KEEP_RECENT_TOOL_RESULTS = 6;
    private static final Set<String> DEFAULT_COMPACTABLE_TOOLS = Set.of("read", "grep", "glob", "bash");

    private final Clock clock;
    private final int keepRecentToolResults;
    private ToolMicroCompactState state;

    public DefaultToolMicroCompactor() {
        this(Clock.systemUTC());
    }

    public DefaultToolMicroCompactor(Clock clock) {
        this(clock, DEFAULT_KEEP_RECENT_TOOL_RESULTS);
    }

    public DefaultToolMicroCompactor(Clock clock, int keepRecentToolResults) {
        this.clock = clock;
        this.keepRecentToolResults = Math.max(1, keepRecentToolResults);
    }

    @Override
    public synchronized ToolMicroCompactResult compact(ToolMicroCompactRequest request) {
        if (request == null || request.context() == null) {
            return new ToolMicroCompactResult(request == null ? null : request.context(), List.of());
        }
        Instant now = clock.instant();
        CacheEpoch epoch = CacheEpoch.from(request);
        List<String> branchFingerprint = branchFingerprint(request);
        if (isHot(epoch, branchFingerprint, now)) {
            state = state.withLastRequestAt(now);
            return new ToolMicroCompactResult(request.context(), List.of());
        }

        List<String> projectedToolUseIds = selectProjectedToolUseIds(request.context());
        state = new ToolMicroCompactState(epoch, branchFingerprint, now, List.copyOf(projectedToolUseIds));
        ContextSnapshot projectedContext = project(request.context(), new HashSet<>(projectedToolUseIds));
        return new ToolMicroCompactResult(projectedContext, List.copyOf(projectedToolUseIds));
    }

    @Override
    public synchronized void reset() {
        state = null;
    }

    private boolean isHot(CacheEpoch epoch, List<String> branchFingerprint, Instant now) {
        return state != null
            && state.epoch().equals(epoch)
            && isLinearExtension(state.branchFingerprint(), branchFingerprint)
            && !Duration.between(state.lastRequestAt(), now).minus(HOT_WINDOW).isPositive();
    }

    private boolean isLinearExtension(List<String> previousMessageIds, List<String> currentMessageIds) {
        if (currentMessageIds.size() < previousMessageIds.size()) {
            return false;
        }
        for (int index = 0; index < previousMessageIds.size(); index++) {
            if (!previousMessageIds.get(index).equals(currentMessageIds.get(index))) {
                return false;
            }
        }
        return true;
    }

    private List<String> selectProjectedToolUseIds(ContextSnapshot context) {
        Map<String, String> toolNamesById = toolNamesById(context.messages());
        int currentTurnStart = currentTurnStart(context.messages());
        List<String> candidates = new ArrayList<>();
        for (int index = 0; index < currentTurnStart; index++) {
            AgentMessage message = context.messages().get(index);
            if (message.kind() != MessageKind.TOOL_RESULT || message.content() == null) {
                continue;
            }
            for (ContentBlock block : message.content()) {
                if (!(block instanceof ToolResultContentBlock resultBlock)) {
                    continue;
                }
                String toolName = normalizeToolName(toolNamesById.get(resultBlock.toolUseId()));
                if (!resultBlock.error() && DEFAULT_COMPACTABLE_TOOLS.contains(toolName)) {
                    candidates.add(resultBlock.toolUseId());
                }
            }
        }
        int clearCount = Math.max(0, candidates.size() - keepRecentToolResults);
        if (clearCount == 0) {
            return List.of();
        }
        return List.copyOf(new LinkedHashSet<>(candidates.subList(0, clearCount)));
    }

    private Map<String, String> toolNamesById(List<AgentMessage> messages) {
        Map<String, String> toolNames = new HashMap<>();
        for (AgentMessage message : messages) {
            if (message.kind() != MessageKind.TOOL_CALL || message.content() == null) {
                continue;
            }
            for (ContentBlock block : message.content()) {
                if (block instanceof ToolCallContentBlock toolCall) {
                    toolNames.put(toolCall.toolUseId(), normalizeToolName(toolCall.toolName()));
                }
            }
        }
        return toolNames;
    }

    private String normalizeToolName(String toolName) {
        return toolName == null ? "" : toolName.toLowerCase(java.util.Locale.ROOT);
    }

    private int currentTurnStart(List<AgentMessage> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index).role() == cn.lypi.contracts.context.MessageRole.USER) {
                return index;
            }
        }
        return messages.size();
    }

    private ContextSnapshot project(ContextSnapshot context, Set<String> projectedToolUseIds) {
        if (projectedToolUseIds.isEmpty()) {
            return context;
        }
        List<AgentMessage> projectedMessages = context.messages().stream()
            .map(message -> projectMessage(message, projectedToolUseIds))
            .toList();
        return new ContextSnapshot(
            context.systemPrompt(),
            projectedMessages,
            context.model(),
            context.thinkingLevel(),
            context.mode(),
            context.permissionRuntimeState(),
            context.budget()
        );
    }

    private List<String> branchFingerprint(ToolMicroCompactRequest request) {
        if (request.branchEntryIds() != null && !request.branchEntryIds().isEmpty()) {
            return List.copyOf(request.branchEntryIds());
        }
        return branchMessageIds(request.context());
    }

    private List<String> branchMessageIds(ContextSnapshot context) {
        return context.messages().stream()
            .map(AgentMessage::id)
            .toList();
    }

    private AgentMessage projectMessage(AgentMessage message, Set<String> projectedToolUseIds) {
        if (message.kind() != MessageKind.TOOL_RESULT || message.content() == null) {
            return message;
        }
        boolean changed = false;
        List<ContentBlock> blocks = new ArrayList<>();
        for (ContentBlock block : message.content()) {
            if (block instanceof ToolResultContentBlock resultBlock
                && projectedToolUseIds.contains(resultBlock.toolUseId())
                && !CLEARED_TOOL_RESULT_TEXT.equals(resultBlock.text())) {
                blocks.add(new ToolResultContentBlock(
                    resultBlock.toolUseId(),
                    CLEARED_TOOL_RESULT_TEXT,
                    resultBlock.error(),
                    resultBlock.metadata()
                ));
                changed = true;
            } else {
                blocks.add(block);
            }
        }
        if (!changed) {
            return message;
        }
        return new AgentMessage(
            message.id(),
            message.role(),
            message.kind(),
            List.copyOf(blocks),
            message.timestamp(),
            message.usage(),
            message.stopReason()
        );
    }

    private record ToolMicroCompactState(
        CacheEpoch epoch,
        List<String> branchFingerprint,
        Instant lastRequestAt,
        List<String> projectedToolUseIds
    ) {
        private ToolMicroCompactState withLastRequestAt(Instant lastRequestAt) {
            return new ToolMicroCompactState(epoch, branchFingerprint, lastRequestAt, projectedToolUseIds);
        }
    }

    private record CacheEpoch(
        String sessionId,
        String provider,
        String model,
        String thinkingLevel,
        String systemPromptHash,
        String toolSchemaHash
    ) {
        private static CacheEpoch from(ToolMicroCompactRequest request) {
            ContextSnapshot context = request.context();
            ModelSelection model = context.model();
            return new CacheEpoch(
                request.sessionId(),
                model == null ? "" : Objects.toString(model.provider(), ""),
                model == null ? "" : Objects.toString(model.modelId(), ""),
                Objects.toString(context.thinkingLevel(), ""),
                context.systemPrompt() == null ? "" : Objects.toString(context.systemPrompt().contentHash(), ""),
                toolSchemaHash(request.tools())
            );
        }

        private static String toolSchemaHash(ToolRegistrySnapshot tools) {
            if (tools == null || tools.tools() == null) {
                return "";
            }
            StringBuilder builder = new StringBuilder();
            for (ToolDescriptor tool : tools.tools()) {
                builder.append(tool.name())
                    .append(':')
                    .append(tool.aliases())
                    .append(':')
                    .append(tool.description())
                    .append(':')
                    .append(canonicalSchema(tool))
                    .append(':')
                    .append(tool.readOnly())
                    .append(':')
                    .append(tool.destructive())
                    .append('|');
            }
            return sha256Hex(builder.toString());
        }

        private static String canonicalSchema(ToolDescriptor tool) {
            if (tool.inputSchema() == null || tool.inputSchema().value() == null) {
                return "";
            }
            return canonicalValue(tool.inputSchema().value());
        }

        private static String canonicalValue(Object value) {
            if (value instanceof Map<?, ?> map) {
                TreeMap<String, Object> sorted = new TreeMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    sorted.put(Objects.toString(entry.getKey(), ""), entry.getValue());
                }
                StringBuilder builder = new StringBuilder("{");
                boolean first = true;
                for (Map.Entry<String, Object> entry : sorted.entrySet()) {
                    if (!first) {
                        builder.append(',');
                    }
                    builder.append(canonicalText(entry.getKey())).append('=').append(canonicalValue(entry.getValue()));
                    first = false;
                }
                return builder.append('}').toString();
            }
            if (value instanceof Iterable<?> iterable) {
                StringBuilder builder = new StringBuilder("[");
                boolean first = true;
                for (Object item : iterable) {
                    if (!first) {
                        builder.append(',');
                    }
                    builder.append(canonicalValue(item));
                    first = false;
                }
                return builder.append(']').toString();
            }
            return canonicalText(Objects.toString(value, ""));
        }

        private static String canonicalText(String value) {
            String safeValue = Objects.toString(value, "");
            return safeValue.length() + ":" + safeValue;
        }

        private static String sha256Hex(String value) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 is not available", exception);
            }
        }
    }
}
