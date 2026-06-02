package cn.lypi.resource;

import cn.lypi.contracts.resource.ResourceDiagnostic;
import java.util.List;

public record PromptRenderResult(
    String content,
    List<ResourceDiagnostic> diagnostics
) {}
