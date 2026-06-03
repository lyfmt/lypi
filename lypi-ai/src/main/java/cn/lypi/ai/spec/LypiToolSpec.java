package cn.lypi.ai.spec;

import java.util.Map;

public record LypiToolSpec(
    String name,
    String description,
    Map<String, Object> inputSchema
) {
    public LypiToolSpec {
        inputSchema = Map.copyOf(inputSchema);
    }
}
