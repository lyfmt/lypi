package cn.lypi.contracts.tui;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

public record SessionResumeInfo(
    Path path,
    String sessionId,
    Path cwd,
    Optional<Path> parentSessionPath,
    String leafId,
    Instant created,
    Instant modified,
    int messageCount,
    String firstMessage,
    String allMessagesText
) {
    public SessionResumeInfo {
        parentSessionPath = parentSessionPath == null ? Optional.empty() : parentSessionPath;
        firstMessage = firstMessage == null || firstMessage.isBlank() ? "(no messages)" : firstMessage;
        allMessagesText = allMessagesText == null ? "" : allMessagesText;
    }
}
