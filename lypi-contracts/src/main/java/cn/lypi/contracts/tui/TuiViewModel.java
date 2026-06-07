package cn.lypi.contracts.tui;

import java.util.List;
import java.util.Optional;

public record TuiViewModel(
    List<TuiBlock> blocks,
    StatusBarState statusBar,
    List<SessionFileView> files,
    Optional<PermissionPromptView> permissionPrompt,
    Optional<DiffView> diffView
) {
    public TuiViewModel {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        files = files == null ? List.of() : List.copyOf(files);
        permissionPrompt = permissionPrompt == null ? Optional.empty() : permissionPrompt;
        diffView = diffView == null ? Optional.empty() : diffView;
    }
}
