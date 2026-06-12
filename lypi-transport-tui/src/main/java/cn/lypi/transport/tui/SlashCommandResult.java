package cn.lypi.transport.tui;

import cn.lypi.contracts.tui.SessionRuntimeState;
import java.util.Optional;

record SlashCommandResult(
    boolean matched,
    boolean consumed,
    Optional<String> prompt,
    Optional<String> message,
    Optional<String> notice,
    boolean stateChanged,
    Optional<SessionRuntimeState> runtimeState
) {
    static SlashCommandResult notMatched() {
        return new SlashCommandResult(false, false, Optional.empty(), Optional.empty(), Optional.empty(), false, Optional.empty());
    }

    static SlashCommandResult consumedCommand() {
        return new SlashCommandResult(true, true, Optional.empty(), Optional.empty(), Optional.empty(), false, Optional.empty());
    }

    static SlashCommandResult error(String message) {
        return new SlashCommandResult(true, true, Optional.empty(), Optional.of(message == null ? "" : message), Optional.empty(), false, Optional.empty());
    }

    static SlashCommandResult notice(String message) {
        return new SlashCommandResult(true, true, Optional.empty(), Optional.empty(), Optional.of(message == null ? "" : message), false, Optional.empty());
    }

    static SlashCommandResult stateChangedNotice(String message) {
        return new SlashCommandResult(true, true, Optional.empty(), Optional.empty(), Optional.of(message == null ? "" : message), true, Optional.empty());
    }

    static SlashCommandResult newSession(SessionRuntimeState state) {
        String sessionId = state == null ? "" : state.sessionId();
        return new SlashCommandResult(
            true,
            true,
            Optional.empty(),
            Optional.empty(),
            Optional.of("new session: " + sessionId),
            true,
            Optional.ofNullable(state)
        );
    }

    static SlashCommandResult submitPrompt(String prompt) {
        return new SlashCommandResult(true, false, Optional.of(prompt == null ? "" : prompt), Optional.empty(), Optional.empty(), false, Optional.empty());
    }
}
