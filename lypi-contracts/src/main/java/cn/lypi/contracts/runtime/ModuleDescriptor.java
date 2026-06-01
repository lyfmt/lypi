package cn.lypi.contracts.runtime;

import java.util.List;

public record ModuleDescriptor(
    String name,
    String responsibility,
    List<String> dependsOn,
    List<String> exposedPorts
) {}

