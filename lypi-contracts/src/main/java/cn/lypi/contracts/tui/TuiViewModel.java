package cn.lypi.contracts.tui;

import java.util.List;
import java.util.Optional;

public record TuiViewModel(
    List<MessageView> messages,
    StatusBarState statusBar,
    Optional<PermissionPromptView> permissionPrompt,
    Optional<DiffView> diffView
) {}

