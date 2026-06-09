package cn.lypi.contracts.subagent;

import java.util.Optional;

public record MailboxCommandResult(
    boolean success,
    Optional<MailboxMessage> message,
    Optional<String> errorMessage
) {
    public MailboxCommandResult {
        message = message == null ? Optional.empty() : message;
        errorMessage = errorMessage == null ? Optional.empty() : errorMessage;
    }

    public static MailboxCommandResult success(MailboxMessage message) {
        return new MailboxCommandResult(true, Optional.ofNullable(message), Optional.empty());
    }

    public static MailboxCommandResult failure(String errorMessage) {
        return new MailboxCommandResult(false, Optional.empty(), Optional.ofNullable(errorMessage));
    }
}
