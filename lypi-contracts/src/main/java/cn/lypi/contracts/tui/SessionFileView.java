package cn.lypi.contracts.tui;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record SessionFileView(
    Path path,
    Set<FileUsageKind> operations,
    Instant lastResultAt,
    Map<String, Object> metadata
) {
    public SessionFileView {
        Objects.requireNonNull(path, "path");
        operations = operations == null ? Set.of() : Set.copyOf(operations);
        Objects.requireNonNull(lastResultAt, "lastResultAt");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
