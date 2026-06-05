package cn.lypi.contracts.tool;

import java.util.Map;

/**
 * 表示工具结果在事件流中的短摘要。
 */
public record ToolResultSummary(
    String title,
    String summary,
    boolean error,
    Integer exitCode,
    boolean timedOut,
    long outputBytes,
    Map<String, Object> metadata
) {
    public ToolResultSummary {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
