package cn.lypi.contracts.skill;

import cn.lypi.contracts.resource.ResourceDiagnostic;
import java.util.List;

public record SkillIndex(
    List<SkillDescriptor> skills,
    List<ResourceDiagnostic> diagnostics
) {}

