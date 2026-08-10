package cn.lypi.contracts.session;

import java.time.Instant;
import java.util.Objects;

/**
 * Append-only fact recording a shell state transition on a session branch.
 */
public record ShellStateChangeEntry(
    String id,
    String parentId,
    ShellState shellState,
    Instant timestamp
) implements SessionEntry {
    public ShellStateChangeEntry {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(shellState, "shellState must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
    }
}
