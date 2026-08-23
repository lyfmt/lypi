package cn.lycode.ai.spec;

import java.util.Map;

public record LyCodeToolSpec(
    String name,
    String description,
    Map<String, Object> inputSchema
) {
    public LyCodeToolSpec {
        inputSchema = Map.copyOf(inputSchema);
    }
}
