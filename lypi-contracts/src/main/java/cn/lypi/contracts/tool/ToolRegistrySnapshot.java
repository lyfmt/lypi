package cn.lypi.contracts.tool;

import java.util.List;

public record ToolRegistrySnapshot(
    List<ToolDescriptor> tools
) {}

