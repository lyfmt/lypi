package cn.lypi.transport.tui;

import cn.lypi.contracts.event.SessionStateEvent;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContentBlock;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.ToolCallContentBlock;
import cn.lypi.contracts.tui.DiffView;
import cn.lypi.contracts.tui.PermissionPromptView;
import cn.lypi.contracts.tui.SessionFileView;
import cn.lypi.contracts.tui.SessionRuntimeState;
import cn.lypi.contracts.tui.StatusBarState;
import cn.lypi.contracts.tui.TuiErrorBlock;
import cn.lypi.contracts.tui.TuiBlock;
import cn.lypi.contracts.tui.TuiMessageBlock;
import cn.lypi.contracts.tui.TuiThinkingBlock;
import cn.lypi.contracts.tui.TuiToolBlock;
import cn.lypi.contracts.tui.TuiToolState;
import cn.lypi.contracts.tui.TuiViewModel;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class TuiRenderState {
    private final List<TuiBlock> blocks = new ArrayList<>();
    private final List<SessionFileView> files = new ArrayList<>();
    private final Map<String, Integer> blockIndexes = new HashMap<>();
    private final Map<String, Integer> toolIndexes = new HashMap<>();
    private PermissionPromptView permissionPrompt;
    private DiffView diffView;
    private StatusBarState statusBar = new StatusBarState("", "", "ready", "");
    private String agentMode = "ready";
    private boolean runtimeInterruptibleTool;
    private final Set<String> runningToolUseIds = new HashSet<>();
    private String activeTurnId;
    private Instant activeTurnStartedAt;
    private Instant lastTurnObservedAt;
    private String lastTurnDurationLine;
    private String retryLine;
    private String compactLine;
    private String interruptLine;

    TuiRenderState() {
    }

    TuiRenderState(SessionRuntimeState runtimeState) {
        configure(runtimeState);
    }

    List<TuiBlock> blocks() {
        return blocks;
    }

    Optional<Integer> blockIndex(String blockId) {
        return Optional.ofNullable(blockIndexes.get(blockId));
    }

    Optional<Integer> toolIndex(String toolUseId) {
        return Optional.ofNullable(toolIndexes.get(toolUseId));
    }

    void addBlock(TuiBlock block) {
        blocks.add(block);
        int index = blocks.size() - 1;
        blockIndexes.put(block.blockId(), index);
        if (block instanceof TuiToolBlock tool) {
            toolIndexes.put(tool.toolUseId(), index);
        }
    }

    void putBlock(int index, TuiBlock block) {
        TuiBlock previous = blocks.set(index, block);
        if (!previous.blockId().equals(block.blockId())) {
            blockIndexes.remove(previous.blockId());
        }
        if (previous instanceof TuiToolBlock tool
            && (!(block instanceof TuiToolBlock nextTool) || !tool.toolUseId().equals(nextTool.toolUseId()))) {
            toolIndexes.remove(tool.toolUseId());
        }
        blockIndexes.put(block.blockId(), index);
        if (block instanceof TuiToolBlock tool) {
            toolIndexes.put(tool.toolUseId(), index);
        }
    }

    void putToolIndex(String toolUseId, int index) {
        toolIndexes.put(toolUseId, index);
    }

    void rebuildIndexes() {
        blockIndexes.clear();
        toolIndexes.clear();
        for (int index = 0; index < blocks.size(); index++) {
            TuiBlock block = blocks.get(index);
            blockIndexes.put(block.blockId(), index);
            if (block instanceof TuiToolBlock tool) {
                toolIndexes.put(tool.toolUseId(), index);
            }
        }
    }

    void permissionPrompt(PermissionPromptView permissionPrompt) {
        this.permissionPrompt = permissionPrompt;
    }

    void clearPermissionPrompt() {
        this.permissionPrompt = null;
    }

    void diffView(DiffView diffView) {
        this.diffView = diffView;
    }

    void clearDiffView() {
        this.diffView = null;
    }

    void configure(SessionRuntimeState runtimeState) {
        if (runtimeState == null) {
            statusBar = new StatusBarState("", "", "ready", "");
            agentMode = "ready";
            runtimeInterruptibleTool = false;
            runningToolUseIds.clear();
            clearRuntimeLines();
            return;
        }
        agentMode = enumLabel(runtimeState.agentMode());
        runtimeInterruptibleTool = runtimeState.hasInterruptibleTool();
        replaceBlocks(projectTranscript(runtimeState.transcript()));
        runningToolUseIds.clear();
        clearRuntimeLines();
        statusBar = new StatusBarState(
            valueOrEmpty(runtimeState.sessionId()),
            modelLabel(runtimeState),
            currentMode(),
            enumLabel(runtimeState.permissionMode()),
            pathLabel(runtimeState.cwd()),
            valueOrEmpty(runtimeState.currentBranchLeafId()),
            budgetLabel(runtimeState),
            runtimeState.hasInterruptibleTool()
        );
    }

    private void replaceBlocks(List<TuiBlock> nextBlocks) {
        blocks.clear();
        blocks.addAll(nextBlocks);
        rebuildIndexes();
    }

    private List<TuiBlock> projectTranscript(List<AgentMessage> transcript) {
        if (transcript == null || transcript.isEmpty()) {
            return List.of();
        }
        List<TuiBlock> projected = new ArrayList<>();
        for (AgentMessage message : transcript) {
            for (int index = 0; index < message.content().size(); index++) {
                ContentBlock block = message.content().get(index);
                String blockId = message.id() + ":" + block.kind().name().toLowerCase() + ":" + index;
                switch (block.kind()) {
                    case TEXT -> projected.add(new TuiMessageBlock(
                        blockId,
                        message.id(),
                        roleName(message.role()),
                        block.text(),
                        false
                    ));
                    case THINKING -> projected.add(new TuiThinkingBlock(
                        blockId,
                        message.id(),
                        block.text(),
                        false,
                        false
                    ));
                    case ERROR -> projected.add(new TuiErrorBlock(blockId, block.text()));
                    case TOOL_CALL -> projected.add(projectToolCall(message.id(), block, blockId));
                    case TOOL_RESULT -> {
                    }
                    default -> {
                    }
                }
            }
        }
        return projected;
    }

    private TuiToolBlock projectToolCall(String messageId, ContentBlock block, String blockId) {
        String toolUseId = block instanceof ToolCallContentBlock toolCall
            ? firstNonBlank(toolCall.toolUseId(), metadataString(block.metadata(), "toolUseId", blockId))
            : metadataString(block.metadata(), "toolUseId", blockId);
        String toolName = block instanceof ToolCallContentBlock toolCall
            ? firstNonBlank(toolCall.toolName(), metadataString(block.metadata(), "toolName", "unknown"))
            : metadataString(block.metadata(), "toolName", "unknown");
        String label = metadataString(block.metadata(), "inputSummary", firstNonBlank(block.text(), toolName));
        return new TuiToolBlock(
            "tool:" + toolUseId,
            messageId,
            toolUseId,
            toolName,
            TuiToolState.PENDING,
            label,
            false
        );
    }

    void toolStarted(String toolUseId) {
        if (toolUseId != null && !toolUseId.isBlank()) {
            runningToolUseIds.add(toolUseId);
        }
        statusBar = withMode(currentMode());
    }

    void toolEnded(String toolUseId) {
        if (toolUseId != null && !toolUseId.isBlank()) {
            runningToolUseIds.remove(toolUseId);
        }
        runtimeInterruptibleTool = false;
        statusBar = withMode(currentMode());
    }

    void sessionStarted(String sessionId) {
        statusBar = new StatusBarState(
            valueOrEmpty(sessionId),
            statusBar.model(),
            statusBar.mode(),
            statusBar.permissionMode(),
            statusBar.cwd(),
            statusBar.branchLeafId(),
            statusBar.budget(),
            statusBar.hasInterruptibleTool()
        );
    }

    void sessionStateChanged(SessionStateEvent event) {
        agentMode = enumLabel(event.agentMode());
        statusBar = new StatusBarState(
            valueOrEmpty(event.sessionId()),
            event.model() == null ? "" : valueOrEmpty(event.model().modelId()),
            currentMode(),
            enumLabel(event.permissionMode()),
            statusBar.cwd(),
            valueOrEmpty(event.leafId()),
            statusBar.budget(),
            statusBar.hasInterruptibleTool()
        );
    }

    void turnStarted(String turnId, Instant startedAt, Instant observedAt) {
        activeTurnId = valueOrEmpty(turnId);
        activeTurnStartedAt = startedAt;
        lastTurnObservedAt = observedAt == null ? startedAt : observedAt;
        lastTurnDurationLine = "";
        interruptLine = "";
        statusBar = withMode("running");
    }

    void turnEnded(long durationMillis) {
        activeTurnId = "";
        activeTurnStartedAt = null;
        lastTurnObservedAt = null;
        retryLine = "";
        compactLine = "";
        interruptLine = "";
        lastTurnDurationLine = "worked " + formatTurnDuration(durationMillis);
        statusBar = withMode(currentMode());
    }

    void retryStarted(int attempt, String reason) {
        retryLine = "retrying attempt " + attempt + suffix(reason);
        interruptLine = "";
        statusBar = withMode("running");
    }

    void retryEnded() {
        retryLine = "";
        statusBar = withMode(currentMode());
    }

    void compactStarted(String kind) {
        compactLine = "compacting" + suffix(kind);
        interruptLine = "";
        statusBar = withMode("running");
    }

    void compactEnded() {
        compactLine = "";
        statusBar = withMode(currentMode());
    }

    void interrupted(String reason) {
        runningToolUseIds.clear();
        runtimeInterruptibleTool = false;
        retryLine = "";
        compactLine = "";
        activeTurnId = "";
        activeTurnStartedAt = null;
        lastTurnObservedAt = null;
        lastTurnDurationLine = "";
        interruptLine = "interrupted" + suffix(reason);
        statusBar = withMode(agentMode);
    }

    TuiViewModel view() {
        return new TuiViewModel(
            blocks,
            statusBar,
            runtimeLine(),
            files,
            Optional.ofNullable(permissionPrompt),
            Optional.ofNullable(diffView)
        );
    }

    private StatusBarState withMode(String mode) {
        return new StatusBarState(
            statusBar.sessionId(),
            statusBar.model(),
            mode,
            statusBar.permissionMode(),
            statusBar.cwd(),
            statusBar.branchLeafId(),
            statusBar.budget(),
            runtimeInterruptibleTool || !runningToolUseIds.isEmpty()
        );
    }

    private String currentMode() {
        return agentMode;
    }

    private String runtimeLine() {
        if (compactLine != null && !compactLine.isBlank()) {
            return compactLine;
        }
        if (retryLine != null && !retryLine.isBlank()) {
            return retryLine;
        }
        if (interruptLine != null && !interruptLine.isBlank()) {
            return interruptLine;
        }
        if (activeTurnId != null && !activeTurnId.isBlank()) {
            long elapsedMillis = 0L;
            if (activeTurnStartedAt != null && lastTurnObservedAt != null) {
                elapsedMillis = Math.max(0L, Duration.between(activeTurnStartedAt, lastTurnObservedAt).toMillis());
            }
            return "working (" + formatTurnDuration(elapsedMillis) + ")";
        }
        if (lastTurnDurationLine != null && !lastTurnDurationLine.isBlank()) {
            return lastTurnDurationLine;
        }
        return "";
    }

    private void clearRuntimeLines() {
        activeTurnId = "";
        activeTurnStartedAt = null;
        lastTurnObservedAt = null;
        lastTurnDurationLine = "";
        retryLine = "";
        compactLine = "";
        interruptLine = "";
    }

    static String formatTurnDuration(long durationMillis) {
        long totalSeconds = Math.max(0L, durationMillis / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return hours + "h" + minutes + "m" + seconds + "s";
        }
        if (minutes > 0L) {
            return minutes + "m" + seconds + "s";
        }
        return seconds + "s";
    }

    private String modelLabel(SessionRuntimeState runtimeState) {
        if (runtimeState.model() == null) {
            return "";
        }
        return valueOrEmpty(runtimeState.model().modelId());
    }

    private String enumLabel(Enum<?> value) {
        return value == null ? "" : value.name();
    }

    private String pathLabel(Path path) {
        if (path == null) {
            return "";
        }
        Path fileName = path.getFileName();
        return fileName == null ? path.toString() : fileName.toString();
    }

    private String budgetLabel(SessionRuntimeState runtimeState) {
        if (runtimeState.budget() == null) {
            return "";
        }
        int used = runtimeState.budget().estimatedContextTokens();
        int window = runtimeState.budget().effectiveContextWindow();
        if (window <= 0) {
            return used <= 0 ? "" : used + "tok";
        }
        return used + "/" + window + "tok";
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private String roleName(MessageRole role) {
        if (role == MessageRole.USER) {
            return "user";
        }
        if (role == MessageRole.SYSTEM_LOCAL) {
            return "system";
        }
        if (role == MessageRole.TOOL_RESULT) {
            return "tool";
        }
        return "assistant";
    }

    private String metadataString(Map<String, Object> metadata, String key, String fallback) {
        if (metadata == null) {
            return fallback;
        }
        Object value = metadata.get(key);
        if (value == null) {
            return fallback;
        }
        String text = value.toString();
        return text.isBlank() ? fallback : text;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String suffix(String value) {
        String safe = valueOrEmpty(value);
        return safe.isBlank() ? "" : " " + safe;
    }
}
