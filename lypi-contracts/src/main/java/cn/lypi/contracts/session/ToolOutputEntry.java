package cn.lypi.contracts.session;

import cn.lypi.contracts.tool.ToolOutputRef;
import cn.lypi.contracts.tool.ToolResultSummary;
import java.time.Instant;
import java.util.Map;

public record ToolOutputEntry(
    String id,
    String parentId,
    String toolUseId,
    ToolOutputRef outputRef,
    ToolResultSummary summary,
    String contentHash,
    long byteLength,
    Map<String, Object> metadata,
    Instant timestamp
) implements SessionEntry {
    public ToolOutputEntry {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
