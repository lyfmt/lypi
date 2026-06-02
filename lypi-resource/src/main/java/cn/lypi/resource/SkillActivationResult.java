package cn.lypi.resource;

import cn.lypi.contracts.resource.ResourceDiagnostic;
import cn.lypi.contracts.skill.SkillActivation;
import java.util.List;

public record SkillActivationResult(
    SkillActivation activation,
    String body,
    List<ResourceDiagnostic> diagnostics
) {}
