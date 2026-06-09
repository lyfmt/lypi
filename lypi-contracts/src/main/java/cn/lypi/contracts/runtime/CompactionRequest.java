package cn.lypi.contracts.runtime;

import cn.lypi.contracts.common.AbortSignal;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public record CompactionRequest(
    String sessionId,
    Optional<String> leafEntryId,
    Path cwd,
    AbortSignal abortSignal
) {
    public CompactionRequest {
        sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        leafEntryId = leafEntryId == null ? Optional.empty() : leafEntryId;
        cwd = cwd == null ? Path.of(".") : cwd;
        abortSignal = abortSignal == null ? () -> false : abortSignal;
    }
}
