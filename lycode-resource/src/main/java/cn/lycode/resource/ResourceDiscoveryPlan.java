package cn.lycode.resource;

import cn.lycode.contracts.resource.ResourceDiagnostic;
import java.nio.file.Path;
import java.util.List;

record ResourceDiscoveryPlan(
    Path projectRoot,
    Path cwd,
    List<ResourceLocation> locations,
    List<ResourceDiagnostic> diagnostics
) {}
