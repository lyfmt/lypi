package cn.lypi.transport.tui;

import cn.lypi.contracts.tui.DiffView;
import cn.lypi.contracts.tui.PermissionPromptView;
import cn.lypi.contracts.tui.SessionFileView;
import cn.lypi.contracts.tui.StatusBarState;
import cn.lypi.contracts.tui.TuiBlock;
import cn.lypi.contracts.tui.TuiViewModel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class TuiRenderState {
    private final List<TuiBlock> blocks = new ArrayList<>();
    private final List<SessionFileView> files = new ArrayList<>();
    private final Map<String, Integer> blockIndexes = new HashMap<>();
    private final Map<String, Integer> toolIndexes = new HashMap<>();
    private PermissionPromptView permissionPrompt;
    private DiffView diffView;
    private String mode = "ready";

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

    void mode(String mode) {
        this.mode = mode == null || mode.isBlank() ? "ready" : mode;
    }

    TuiViewModel view() {
        return new TuiViewModel(
            blocks,
            new StatusBarState("", "", mode, ""),
            files,
            Optional.ofNullable(permissionPrompt),
            Optional.ofNullable(diffView)
        );
    }
}
