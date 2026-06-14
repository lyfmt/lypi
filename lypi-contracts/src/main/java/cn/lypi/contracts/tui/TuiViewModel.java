package cn.lypi.contracts.tui;

import java.util.List;
import java.util.Optional;

public record TuiViewModel(
    List<TuiBlock> blocks,
    StatusBarState statusBar,
    String runtimeLine,
    List<SessionFileView> files,
    Optional<PermissionPromptView> permissionPrompt,
    Optional<DiffView> diffView
) {
    public TuiViewModel {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        runtimeLine = runtimeLine == null ? "" : runtimeLine;
        files = files == null ? List.of() : List.copyOf(files);
        permissionPrompt = permissionPrompt == null ? Optional.empty() : permissionPrompt;
        diffView = diffView == null ? Optional.empty() : diffView;
    }

    public TuiViewModel(
        List<TuiBlock> blocks,
        StatusBarState statusBar,
        List<SessionFileView> files,
        Optional<PermissionPromptView> permissionPrompt,
        Optional<DiffView> diffView
    ) {
        this(blocks, statusBar, "", files, permissionPrompt, diffView);
    }
}
