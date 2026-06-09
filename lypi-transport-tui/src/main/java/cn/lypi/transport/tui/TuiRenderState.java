package cn.lypi.transport.tui;

import cn.lypi.contracts.tui.DiffView;
import cn.lypi.contracts.tui.PermissionPromptView;
import cn.lypi.contracts.tui.SessionFileView;
import cn.lypi.contracts.tui.SessionRuntimeState;
import cn.lypi.contracts.tui.StatusBarState;
import cn.lypi.contracts.tui.TuiBlock;
import cn.lypi.contracts.tui.TuiToolBlock;
import cn.lypi.contracts.tui.TuiViewModel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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

    void turnStarted(String turnId) {
        activeTurnId = valueOrEmpty(turnId);
        interruptLine = "";
        statusBar = withMode("running");
    }

    void turnEnded() {
        activeTurnId = "";
        retryLine = "";
        compactLine = "";
        interruptLine = "";
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
        return runtimeInterruptibleTool || !runningToolUseIds.isEmpty() ? "running" : agentMode;
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
            return "turn running " + activeTurnId;
        }
        return "";
    }

    private void clearRuntimeLines() {
        activeTurnId = "";
        retryLine = "";
        compactLine = "";
        interruptLine = "";
    }

    private String modelLabel(SessionRuntimeState runtimeState) {
        if (runtimeState.model() == null) {
            return "";
        }
        String modelId = valueOrEmpty(runtimeState.model().modelId());
        String thinking = enumLabel(runtimeState.thinkingLevel());
        if (modelId.isBlank() || thinking.isBlank()) {
            return modelId;
        }
        return modelId + ":thinking=" + thinking;
    }

    private String enumLabel(Enum<?> value) {
        return value == null ? "" : value.name().toLowerCase(Locale.ROOT);
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

    private String suffix(String value) {
        String safe = valueOrEmpty(value);
        return safe.isBlank() ? "" : " " + safe;
    }
}
