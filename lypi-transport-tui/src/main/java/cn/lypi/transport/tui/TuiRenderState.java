package cn.lypi.transport.tui;

import cn.lypi.contracts.tui.DiffView;
import cn.lypi.contracts.tui.PermissionPromptView;
import cn.lypi.contracts.tui.SessionFileView;
import cn.lypi.contracts.tui.SessionRuntimeState;
import cn.lypi.contracts.tui.StatusBarState;
import cn.lypi.contracts.tui.TuiBlock;
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
        blockIndexes.put(block.blockId(), blocks.size() - 1);
    }

    void putBlock(int index, TuiBlock block) {
        blocks.set(index, block);
        blockIndexes.put(block.blockId(), index);
    }

    void putToolIndex(String toolUseId, int index) {
        toolIndexes.put(toolUseId, index);
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
            return;
        }
        agentMode = enumLabel(runtimeState.agentMode());
        runtimeInterruptibleTool = runtimeState.hasInterruptibleTool();
        runningToolUseIds.clear();
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

    TuiViewModel view() {
        return new TuiViewModel(
            blocks,
            statusBar,
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
}
