package cn.lypi.contracts.tui;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

public record GitDiffFileView(
    Path path,
    GitDiffStatus status,
    String summary,
    Map<String, Object> metadata
) {
    public GitDiffFileView {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(status, "status");
        summary = summary == null ? "" : summary;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
