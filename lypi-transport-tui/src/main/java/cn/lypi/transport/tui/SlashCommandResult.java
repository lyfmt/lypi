package cn.lypi.transport.tui;

import java.util.Optional;

record SlashCommandResult(
    boolean matched,
    boolean consumed,
    Optional<String> prompt,
    Optional<String> message,
    Optional<String> notice
) {
    static SlashCommandResult notMatched() {
        return new SlashCommandResult(false, false, Optional.empty(), Optional.empty(), Optional.empty());
    }

    static SlashCommandResult consumedCommand() {
        return new SlashCommandResult(true, true, Optional.empty(), Optional.empty(), Optional.empty());
    }

    static SlashCommandResult error(String message) {
        return new SlashCommandResult(true, true, Optional.empty(), Optional.of(message == null ? "" : message), Optional.empty());
    }

    static SlashCommandResult notice(String message) {
        return new SlashCommandResult(true, true, Optional.empty(), Optional.empty(), Optional.of(message == null ? "" : message));
    }

    static SlashCommandResult submitPrompt(String prompt) {
        return new SlashCommandResult(true, false, Optional.of(prompt == null ? "" : prompt), Optional.empty(), Optional.empty());
    }
}
