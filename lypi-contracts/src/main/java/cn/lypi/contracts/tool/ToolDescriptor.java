package cn.lypi.contracts.tool;

import java.util.List;

public record ToolDescriptor(
    String name,
    List<String> aliases,
    boolean readOnly,
    boolean destructive
) {}

