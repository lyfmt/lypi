package cn.lycode.contracts.tool;

import java.util.List;

public record ToolRegistrySnapshot(
    List<ToolDescriptor> tools
) {}

