package cn.lypi.contracts.tool;

import java.nio.file.Path;
import java.util.Objects;

/**
 * State changes produced by a successful tool execution.
 */
public record ToolStateDelta(Path cwd) {
    public ToolStateDelta {
        Objects.requireNonNull(cwd, "cwd must not be null");
    }
}
