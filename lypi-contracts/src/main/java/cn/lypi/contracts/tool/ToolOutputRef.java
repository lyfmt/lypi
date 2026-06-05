package cn.lypi.contracts.tool;

import java.util.Map;

/**
 * 指向一次工具完整输出的稳定引用。
 *
 * NOTE: storageKind 为 pending 时，location 允许为空字符串。
 */
public record ToolOutputRef(
    String refId,
    String sessionId,
    String toolUseId,
    String mediaType,
    String storageKind,
    String location,
    String contentHash,
    long byteLength,
    Map<String, Object> metadata
) {
    public ToolOutputRef {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
