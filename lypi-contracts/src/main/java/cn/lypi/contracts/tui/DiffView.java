package cn.lypi.contracts.tui;

import java.util.List;
import java.util.Map;

public record DiffView(
    String summary,
    List<GitDiffFileView> files,
    String patch,
    boolean truncated,
    Map<String, Object> metadata
) {
    public DiffView {
        summary = summary == null ? "" : summary;
        files = files == null ? List.of() : List.copyOf(files);
        patch = patch == null ? "" : patch;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
