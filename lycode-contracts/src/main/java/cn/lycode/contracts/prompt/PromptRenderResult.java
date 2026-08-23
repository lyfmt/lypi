package cn.lycode.contracts.prompt;

import cn.lycode.contracts.resource.ResourceDiagnostic;
import java.util.List;

public record PromptRenderResult(
    String content,
    List<ResourceDiagnostic> diagnostics
) {}
