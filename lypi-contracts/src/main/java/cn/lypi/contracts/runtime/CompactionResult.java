package cn.lypi.contracts.runtime;

import java.util.Optional;

public record CompactionResult(
    boolean compacted,
    Optional<String> entryId,
    String message
) {
    public CompactionResult {
        entryId = entryId == null ? Optional.empty() : entryId;
        message = message == null ? "" : message;
    }
}
