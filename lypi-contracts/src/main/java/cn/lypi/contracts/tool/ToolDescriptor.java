package cn.lypi.contracts.tool;

import cn.lypi.contracts.common.JsonSchema;
import java.util.List;
import java.util.Map;

public record ToolDescriptor(
    String name,
    List<String> aliases,
    String description,
    JsonSchema inputSchema,
    boolean readOnly,
    boolean destructive
) {
    public ToolDescriptor(
        String name,
        List<String> aliases,
        boolean readOnly,
        boolean destructive
    ) {
        this(name, aliases, name, new JsonSchema(Map.of()), readOnly, destructive);
    }
}
