package cn.lycode.resource;

import cn.lycode.contracts.resource.ResourceDiagnostic;
import cn.lycode.contracts.skill.SkillActivation;
import java.util.List;

public record SkillActivationResult(
    SkillActivation activation,
    String body,
    List<ResourceDiagnostic> diagnostics
) {}
