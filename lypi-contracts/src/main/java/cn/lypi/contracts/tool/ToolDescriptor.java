package cn.lypi.contracts.tool;

import cn.lypi.contracts.common.JsonSchema;
import java.util.List;
import java.util.Objects;

public record ToolDescriptor(
    String name,
    List<String> aliases,
    String description,
    JsonSchema inputSchema,
    boolean readOnly,
    boolean destructive
) {
    public ToolDescriptor {
        Objects.requireNonNull(name, "name");
        aliases = List.copyOf(Objects.requireNonNull(aliases, "aliases"));
        description = Objects.requireNonNull(description, "description");
        inputSchema = Objects.requireNonNull(inputSchema, "inputSchema");
    }
}
