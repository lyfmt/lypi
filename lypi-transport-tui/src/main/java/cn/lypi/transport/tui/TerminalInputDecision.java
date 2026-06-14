package cn.lypi.transport.tui;

import java.util.Optional;

public record TerminalInputDecision(
    TerminalInputAction action,
    Optional<String> optionId,
    Optional<String> focusTarget
) {
    public TerminalInputDecision {
        action = action == null ? TerminalInputAction.NOOP : action;
        optionId = optionId == null ? Optional.empty() : optionId;
        focusTarget = focusTarget == null ? Optional.empty() : focusTarget;
    }

    public static TerminalInputDecision action(TerminalInputAction action) {
        return new TerminalInputDecision(action, Optional.empty(), Optional.empty());
    }

    public static TerminalInputDecision option(String optionId) {
        return new TerminalInputDecision(
            TerminalInputAction.SUBMIT_PERMISSION_OPTION,
            Optional.ofNullable(optionId),
            Optional.empty()
        );
    }

    public static TerminalInputDecision focus(String focusTarget) {
        return new TerminalInputDecision(
            TerminalInputAction.RESTORE_FOCUS,
            Optional.empty(),
            Optional.ofNullable(focusTarget)
        );
    }
}
